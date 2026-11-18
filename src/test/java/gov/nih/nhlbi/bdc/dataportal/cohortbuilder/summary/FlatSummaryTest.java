package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import com.fasterxml.jackson.databind.json.JsonMapper;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.BdcDpCohortBuilderApplication;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static org.assertj.core.api.Assertions.*;

class FlatSummaryTest {
    @Test void loadsOnlyExactScenariosAndProducesContractEvidence() throws Exception {
        var service=new SummaryService(new ClassPathResource("summary/scenarios.json"));
        var page=service.summary(null,null);
        assertThat(page.release()).isEqualTo("example-release");
        assertThat(page.filter()).isEmpty();
        assertThat(page.focus()).isEmpty();
        assertThat(page.studies()).hasSize(2);
        assertThat(page.studies().get(0).participant_count()).isEqualTo(1200);
        assertThat(page.studies().get(1).participant_count()).isEqualTo(1050);
        var t2d=service.summary(Map.of("summaryId","palette:measurement/FAST_GLUC_BLD"),List.of("MONDO:0005148"));
        assertThat(t2d.studies()).hasSize(2);
        assertThat(t2d.studies().getFirst().participant_count()).isEqualTo(480);
        var json=JsonMapper.builder().build();
        var dir=Path.of("target/summary-contract");Files.createDirectories(dir);
        Files.writeString(dir.resolve("page-load.json"),json.writeValueAsString(page));
        Files.writeString(dir.resolve("t2d.json"),json.writeValueAsString(t2d));
        assertThat(json.readTree(json.writeValueAsString(t2d)).has("records")).isFalse();
        for(var filter:List.of(Map.<String,Object>of(),Map.<String,Object>of("and",List.of()),
                Map.<String,Object>of("condition",Map.of("concept","MONDO:0005148","selection","INCLUDE"))))
            assertThatThrownBy(()->service.summary(filter,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.summary(null,List.of("MONDO:0005148"))).isInstanceOf(ApiException.class);
    }
    @Test void servesSummaryWithoutBackendAndRejectsUnsupportedFilters() throws Exception {
        try(var app=SpringApplication.run(BdcDpCohortBuilderApplication.class,"--server.port=0");
            var http=HttpClient.newHttpClient()) {
            int port=((WebServerApplicationContext)app).getWebServer().getPort();
            var json=JsonMapper.builder().build();
            for(String query:List.of("{ summary { release studies { participant_count } } }",
                    "{ summary(filter:null) { release } }")) {
                var body=call(http,json,port,query);
                assertThat(body.has("errors")).as(body.toString()).isFalse();
                assertThat(body.at("/data/summary/release").asText()).isEqualTo("example-release");
            }
            var failure=call(http,json,port,"{ summary(filter:{condition:{concept:\"MONDO:0005148\",selection:INCLUDE}}) { release } }");
            assertThat(failure.at("/errors/0/extensions/code").asText()).isEqualTo("BAD_USER_INPUT");
            for(String removed:List.of("searchProbe","searchConditions","pageLoadSnapshot"))
                assertThat(call(http,json,port,"{ "+removed+" { __typename } }").has("errors")).isTrue();
            assertThat(app.containsBean("elasticsearchTransport")).isFalse();
            var t2d=call(http,json,port,"{ summary(filter:{summaryId:\"palette:measurement/FAST_GLUC_BLD\"},focus:[\"MONDO:0005148\"]) { studies { participant_count measurements { record_count values { bin_low bin_high participant_count } } } } }");
            assertThat(t2d.has("errors")).as(t2d.toString()).isFalse();
            assertThat(t2d.at("/data/summary/studies/0/participant_count").asInt()).isEqualTo(480);
            assertThat(call(http,json,port,"{ summary { records } }").has("errors")).isTrue();
        }
    }
    private com.fasterxml.jackson.databind.JsonNode call(HttpClient http,JsonMapper json,int port,String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/cb/open/query"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
