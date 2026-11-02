package org.biodatacatalyst.middleware.summary;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.biodatacatalyst.middleware.DataPortalMiddlewareApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SummaryGraphQlTest {
    @Test void exposesOnlyContractSummaryFieldsEvenWithOldProfileName() throws Exception {
        try (var app=SpringApplication.run(DataPortalMiddlewareApplication.class,
                "--server.port=0","--spring.profiles.active=synthetic","--harmonized.enabled=false",
                "--monarch.base-url=http://127.0.0.1:1"); var http=HttpClient.newHttpClient()) {
            int port=((WebServerApplicationContext)app).getWebServer().getPort();
            var mapper=JsonMapper.builder().build();
            String q="""
                query($sourceId:ID!) {
                  summary(sourceId:$sourceId) {
                    release seed filter min_count
                    terms { curie label vocabulary parents }
                    relations { subject predicate object relation_source }
                    research_studies { id name research_project_type program principal_investigator }
                    studies {
                      research_study participant_count
                      participants { id participant_count summarized_slot bin_low bin_high bin_unit values { id participant_count } }
                      demographics { id participant_count summarized_slot values { id participant_count sex race ethnicity } }
                      conditions { id condition_concept participant_count values { id condition_status participant_count record_count } }
                      measurements { id observation_type participant_count values { id bin_low bin_high bin_unit participant_count } }
                      procedures { id participant_count }
                      drug_exposures { id participant_count }
                    }
                  }
                  summaryJson(sourceId:$sourceId)
                  summarySources
                  summaryConcepts(sourceId:$sourceId) { curie }
                }
                """;
            for (String source:java.util.List.of("t2d","t2d-female")) {
                var result=call(http,port,mapper,Map.of("query",q,"variables",Map.of("sourceId",source)));
                assertThat(result.has("errors")).as(result.toString()).isFalse();
                assertThat(result.at("/data/summary/release")).isEqualTo(result.at("/data/summaryJson/release"));
                assertThat(result.at("/data/summary/studies/0/participant_count").asInt()).isEqualTo(source.equals("t2d")?480:230);
                assertThat(result.at("/data/summaryJson").properties()).extracting(Map.Entry::getKey)
                        .containsExactlyInAnyOrder("release","seed","filter","min_count","terms","relations","research_studies","studies");
            }
            String introspection = "";
            for (String inspect : java.util.List.of(
                    "{ __type(name:\"SummaryResponse\") { fields { name } } }",
                    "{ __schema { queryType { fields { name } } } }")) {
                var inspected = call(http,port,mapper,Map.of("query",inspect));
                assertThat(inspected.has("errors")).as(inspected.toString()).isFalse();
                introspection += inspected.toString();
            }
            for (String forbidden:java.util.List.of("participantRecords","entityRecords","participantDetail","definitionParticipants","records","snapshot","cohortFilteringApplied"))
                assertThat(introspection).doesNotContain("\""+forbidden+"\"");
            JsonNode result;
            result=call(http,port,mapper,Map.of("query","{ summary { records } }"));
            assertThat(result.has("errors")).isTrue();
            String selectionQuery = """
                query($include:[ID!]!,$exclude:[ID!]!) {
                  summary(include:$include,exclude:$exclude) {
                    release filter studies { participant_count conditions { condition_concept } }
                  }
                  summaryJson(include:$include,exclude:$exclude)
                }
                """;
            result=call(http,port,mapper,Map.of("query",selectionQuery,"variables",Map.of(
                    "include",java.util.List.of("MONDO:0005148","MONDO:0005015"),
                    "exclude",java.util.List.of("MONDO:0005252"))));
            assertThat(result.has("errors")).as(result.toString()).isFalse();
            assertThat(result.at("/data/summary/studies/0/participant_count").asInt()).isEqualTo(480);
            assertThat(result.at("/data/summary/studies/0/conditions").size()).isEqualTo(1);
            assertThat(result.at("/data/summary/studies/0/conditions/0/condition_concept").asString()).isEqualTo("MONDO:0005148");
            assertThat(result.at("/data/summaryJson/studies/0/conditions").size()).isEqualTo(1);
            assertThat(result.at("/data/summary/filter")).isEqualTo(result.at("/data/summaryJson/filter"));
            result=call(http,port,mapper,Map.of("query","{ summary(sourceId:\"../t2d\") { release } }"));
            assertThat(result.at("/errors/0/extensions/code").asString()).isEqualTo("BAD_USER_INPUT");
        }
    }
    private JsonNode call(HttpClient http,int port,JsonMapper mapper,Map<String,?> body) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/graphql"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        var res=http.send(req,HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return mapper.readTree(res.body());
    }
}
