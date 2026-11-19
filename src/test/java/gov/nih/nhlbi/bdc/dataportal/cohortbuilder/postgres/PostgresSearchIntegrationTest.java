package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.postgres;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.BdcDpCohortBuilderApplication;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryModels.SummaryResponse;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PG_TEST_URL",matches=".+")
class PostgresSearchIntegrationTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private PostgresProperties settings(int min) {
        return new PostgresProperties(true,true,System.getenv("PG_TEST_URL"),"dp_search_test","test-only-password","pg-test",min,15,10000);
    }
    private static Map<String,Object> a(){return PostgresFilterTest.condition("MONDO:0005044","INCLUDE");}
    private static Map<String,Object> b(){return PostgresFilterTest.condition("HP:0000822","INCLUDE");}
    private static BigInteger total(SummaryResponse r){return r.studies().stream().map(s->s.participant_count()).reduce(BigInteger.ZERO,BigInteger::add);}
    private void evidence(String name,SummaryResponse response) throws Exception {
        Path dir=Path.of("target/postgres-contract"); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name+".json"),json.writeValueAsString(response));
    }
    @Test void usesHmSchemaWithRestrictedPublicRole() throws Exception {
        try (Connection connection=DriverManager.getConnection(settings(1).url(),"dp_search_test","test-only-password");
             var statement=connection.createStatement()) {
            try (var result=statement.executeQuery("SELECT current_database(), to_regclass('counting.event') IS NULL")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("bdc_dp_hm_index");
                assertThat(result.getBoolean(2)).isTrue();
            }
            for (String table:List.of("participant_ordinal","participant_attribute","event","release_check")) {
                try (var result=statement.executeQuery("SELECT has_table_privilege('api_public','hm."+table+"','SELECT'), "
                        +"has_table_privilege(current_user,'hm."+table+"','SELECT')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.getBoolean(2)).isTrue();
                }
            }
            try (var result=statement.executeQuery("SELECT has_function_privilege('api_public','hm.create_release_partitions(text)','EXECUTE'), "
                    +"has_function_privilege('rebuild_job','hm.create_release_partitions(text)','EXECUTE'), "
                    +"(SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='hm.event'::regclass)")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isFalse();
                assertThat(result.getBoolean(2)).isTrue();
                assertThat(result.getString(3)).isEqualTo("bdc_dp_owner");
            }
        }
    }
    @Test void queriesRealTablesAndServesGraphQlWithoutElasticsearch() throws Exception {
        var service=new PostgresSearchService(settings(1));
        var page=service.search(null,null);
        assertThat(total(page)).isEqualTo(6); evidence("page-load",page);
        assertThat(page.studies()).hasSize(2);
        var first=page.studies().getFirst();
        assertThat(first.conditions().stream().filter(c->c.condition_concept().equals("MONDO:0005044")).findFirst().orElseThrow().participant_count()).isEqualTo(3);
        var race=first.demographics().stream().filter(d->d.summarized_slot().equals("race")).findFirst().orElseThrow();
        assertThat(race.participant_count()).isEqualTo(2);
        assertThat(race.values().stream().filter(v->v.race().contains("WHITE")).findFirst().orElseThrow().participant_count()).isEqualTo(2);
        // Baseline-only p6 is not counted as an index-age record. Upper bound p4 is outside all bins.
        assertThat(first.participants().getFirst().participant_count()).isEqualTo(3);
        assertThat(first.participants().getFirst().values()).hasSize(2);
        assertThat(page.studies().get(1).participants().getFirst().participant_count()).isEqualTo(1);
        var single=service.search(a(),null);
        assertThat(total(single)).isEqualTo(3); evidence("single-term",single);
        var both=service.search(Map.of("and",List.of(a(),b())),null);
        assertThat(total(both)).isEqualTo(1); evidence("and",both);
        var either=service.search(Map.of("or",List.of(a(),b())),null);
        assertThat(total(either)).isEqualTo(4); evidence("or",either);
        var absent=PostgresFilterTest.condition("MONDO:0005044","EXCLUDE");
        assertThat(total(service.search(absent,null))).isEqualTo(2);
        var mixed=service.search(Map.of("or",List.of(Map.of("and",List.of(a(),b())),absent)),null);
        assertThat(total(mixed)).isEqualTo(3); evidence("mixed",mixed);
        var female=Map.<String,Object>of("summaryId",PostgresFilter.id("demography","sex","FEMALE"));
        assertThat(total(service.search(female,null))).isEqualTo(3);
        assertThat(total(service.search(Map.of("summaryId",PostgresFilter.id("demography","race","WHITE")),null))).isEqualTo(3);
        var focused=service.search(null,List.of("MONDO:0005044"));
        assertThat(total(focused)).isEqualTo(6); evidence("focused",focused);
        assertThat(focused.studies().getFirst().conditions()).hasSize(1);
        var empty=service.search(PostgresFilterTest.condition("ATC:C03","INCLUDE"),null);
        assertThat(empty.studies()).isEmpty(); evidence("empty",empty);
        assertThat(service.search(PostgresFilterTest.condition("X:x');--","INCLUDE"),null).studies()).isEmpty();
        assertThat(total(service.search(null,null))).isEqualTo(6);
        var suppressed=new PostgresSearchService(settings(2)).search(a(),null);
        assertThat(suppressed.studies()).hasSize(1); evidence("suppressed",suppressed);
        var missingRelease=new PostgresProperties(true,true,settings(1).url(),"dp_search_test","test-only-password","missing",1,15,10000);
        assertThatThrownBy(()->new PostgresSearchService(missingRelease).search(null,null)).hasMessageContaining("validation is incomplete");
        var smallLimit=new PostgresProperties(true,true,settings(1).url(),"dp_search_test","test-only-password","pg-test",1,15,1);
        assertThatThrownBy(()->new PostgresSearchService(smallLimit).search(null,null)).hasMessageContaining("exceeds configured limit");
        try(Connection connection=DriverManager.getConnection(settings(1).url(),"dp_search_test","test-only-password")) {
            assertThatThrownBy(()->connection.createStatement().execute("DELETE FROM hm.event"))
                    .isInstanceOf(SQLException.class);
        }
        try(var app=SpringApplication.run(BdcDpCohortBuilderApplication.class,
                "--server.port=0", "--search.enabled=false", "--harmonized.enabled=false",
                "--postgres-search.enabled=true", "--postgres-search.synthetic-data-confirmed=true",
                "--postgres-search.url="+settings(1).url(),"--postgres-search.username=dp_search_test",
                "--postgres-search.password=test-only-password", "--postgres-search.release=pg-test","--postgres-search.min-count=1");
            var http=HttpClient.newHttpClient()) {
            int port=((WebServerApplicationContext)app).getWebServer().getPort();
            String path=app.getEnvironment().getRequiredProperty("spring.graphql.http.path");
            var body=Map.of("query","query($filter:SummaryFilterInput){search(filter:$filter){release focus min_count filter studies{research_study participant_count conditions{id participant_count values{condition_status participant_count}}}}}","variables",Map.of("filter",a()));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var result=json.readTree(response.body());
            assertThat(result.has("errors")).as(response.body()).isFalse();
            assertThat(result.at("/data/search/release").asString()).isEqualTo("pg-test");
            assertThat(result.at("/data/search/studies/0/participant_count").asInt()).isEqualTo(2);
        }
    }
}
