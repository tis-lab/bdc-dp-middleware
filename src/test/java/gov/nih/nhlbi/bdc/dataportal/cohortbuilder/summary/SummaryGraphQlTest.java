package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.BdcDpCohortBuilderApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SummaryGraphQlTest {
    @Test void pageLoadAndBooleanFiltersUseLiveBackend() throws Exception {
        var json=JsonMapper.builder().build();
        var captured=new AtomicReference<JsonNode>();
        var es=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] fixture;
        try(var in=getClass().getResourceAsStream("/summary/elasticsearch-response.json")) { fixture=Objects.requireNonNull(in).readAllBytes(); }
        es.createContext("/participant_1/_search", exchange -> {
            captured.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,fixture.length);
            exchange.getResponseBody().write(fixture); exchange.close();
        });
        es.start();
        try(var app=SpringApplication.run(BdcDpCohortBuilderApplication.class,
                "--server.port=0","--harmonized.enabled=false","--search.enabled=true",
                "--search.endpoint=http://127.0.0.1:"+es.getAddress().getPort(),"--search.index=participant_1",
                "--summary.release=integration-test-release","--summary.min-count=1");
            var http=HttpClient.newHttpClient()) {
            int port=((WebServerApplicationContext)app).getWebServer().getPort();
            String path=app.getEnvironment().getRequiredProperty("spring.graphql.http.path");
            var result=call(http,port,path,json,"{ summary { seed release min_count studies { research_study participant_count conditions { participant_count relationship_to_participant } } } }");
            assertThat(result.has("errors")).as(result.toString()).isFalse();
            assertThat(result.at("/data/summary/seed").isNull()).isTrue();
            assertThat(result.at("/data/summary/studies/0/participant_count").asInt()).isEqualTo(5);
            assertThat(captured.get().at("/query/match_all").isObject()).isTrue();
            for (String unsupported : List.of(
                    "{ summary(seed: \"*\") { release } }",
                    "{ summary(selection: {operator: OR}) { release } }")) {
                result=call(http,port,path,json,unsupported);
                assertThat(result.has("errors")).isTrue();
            }
            captured.set(null);
            result=call(http,port,path,json,"{ summaryJson }");
            assertThat(result.has("errors")).isTrue();
            assertThat(captured.get()).isNull();
            result=call(http,port,path,json,"{ __type(name: \"SummaryFilterInput\") { name } }");
            assertThat(result.at("/data/__type/name").asString()).isEqualTo("SummaryFilterInput");
            String id = resultId("demography|sex|FEMALE");
            String selection = "{and: [{summaryId: \""+id+"\"}, {or: [{summaryId: \""+id+"\"}]}]}";
            result=call(http,port,path,json,"{ summary(filter: "+selection+") { filter studies { participant_count } } }");
            assertThat(result.has("errors")).as(result.toString()).isFalse();
            assertThat(result.at("/data/summary/filter/0").asString()).isEqualTo(id);
            assertThat(captured.get().at("/query/bool/filter/1/bool/minimum_should_match").asInt()).isEqualTo(1);
            result=call(http,port,path,json,"{ summary(filter: {condition: {concept: \"HP:0000822\", selection: EXCLUDE}}) { filter } }");
            assertThat(result.has("errors")).as(result.toString()).isFalse();
            assertThat(captured.get().at("/query/nested/query/bool/filter/1/term/condition.relationship_to_participant").asString()).isEqualTo("ONESELF");
            assertThat(captured.get().at("/query/nested/query/bool/filter/2/term/condition.condition_status").asString()).isEqualTo("ABSENT");
            var conceptQuery=captured.get().path("query");
            result=call(http,port,path,json,"{ summary(filter: {condition: {concept: \"HP:0000822\", status: ABSENT}}) { release } }");
            assertThat(result.has("errors")).as(result.toString()).isFalse();
            assertThat(captured.get().path("query")).isEqualTo(conceptQuery);
            for (String invalid : List.of("{}", "{or: []}", "{summaryId: \"bad\"}", "{and: [], or: []}", "{condition: {concept: \"HP:0000822\"}}", "{condition: {concept: \"HP:0000822\", selection: INCLUDE, status: ABSENT}}")) {
                captured.set(null);
                result=call(http,port,path,json,"{ summary(filter: "+invalid+") { release } }");
                assertThat(result.at("/errors/0/extensions/code").asString()).isEqualTo("BAD_USER_INPUT");
                assertThat(captured.get()).isNull();
            }
            result=call(http,port,path,json,"{ summary(filter: null) { release } }");
            assertThat(result.has("errors")).isFalse();
            assertThat(captured.get().at("/query/match_all").isObject()).isTrue();
        } finally { es.stop(0); }
    }
    private static String resultId(String parts) {
        return "urn:bdc:summary:"+Base64.getUrlEncoder().withoutPadding().encodeToString(parts.getBytes(StandardCharsets.UTF_8));
    }
    private JsonNode call(HttpClient http,int port,String path,JsonMapper json,String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)),StandardCharsets.UTF_8)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
