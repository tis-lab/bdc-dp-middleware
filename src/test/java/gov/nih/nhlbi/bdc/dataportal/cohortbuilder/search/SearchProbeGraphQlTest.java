package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.search;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.BdcDpCohortBuilderApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SearchProbeGraphQlTest {
    private JsonNode call(int port) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/cb/query"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"query":"{ searchProbe { status backend httpStatus elapsedMillis totalHits snapshot { participantCount totalParticipantCount studyCount totalStudyCount datasetCount totalDatasetCount indexRelease lastUpdated unavailableFields } } searchConditions(keyword: \\"PRESENT|ONESELF|MONDO:0005148\\") { keyword totalHits buckets { conditionKey documentCount } sumOtherDocCount docCountErrorUpperBound } }"}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
            return JsonMapper.builder().build().readTree(response.body());
        }
    }
    @Test void bootsWithSearchDisabledAndReportsClearError() throws Exception {
        try (var app = SpringApplication.run(BdcDpCohortBuilderApplication.class,
                "--server.port=0", "--harmonized.enabled=false", "--search.enabled=false")) {
            var result = call(Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort());
            assertThat(result.at("/errors/0/extensions/code").asString()).isEqualTo("UPSTREAM_UNAVAILABLE");
        }
    }
    @Test void executesThroughGraphQlAndReturnsDiagnosticsOnly() throws Exception {
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = ElasticsearchMetadataSearchTest.countResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        backend.start();
        try (var app = SpringApplication.run(BdcDpCohortBuilderApplication.class,
                "--server.port=0", "--harmonized.enabled=false", "--search.enabled=true",
                "--search.endpoint=http://127.0.0.1:" + backend.getAddress().getPort(),
                "--search.index=metadata-test")) {
            var result = call(Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort());
            assertThat(result.has("errors")).as(result.toString()).isFalse();
            assertThat(result.at("/data/searchProbe/status").asString()).isEqualTo("SUCCEEDED");
            assertThat(result.at("/data/searchProbe/httpStatus").asInt()).isEqualTo(200);
            assertThat(result.at("/data/searchProbe/totalHits").asString()).isEqualTo("500");
            assertThat(result.at("/data/searchProbe").size()).isEqualTo(6);
            assertThat(result.at("/data/searchConditions/totalHits").asString()).isEqualTo("500");
            assertThat(result.at("/data/searchProbe/snapshot/participantCount").asInt()).isEqualTo(500);
            assertThat(result.at("/data/searchProbe/snapshot/studyCount").isNull()).isTrue();
            assertThat(result.at("/data/searchConditions/buckets/0/documentCount").asString()).isEqualTo("12");
            assertThat(result.at("/data/searchConditions/sumOtherDocCount").asString()).isEqualTo("3");
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            try (var http = HttpClient.newHttpClient()) {
                var mapper = JsonMapper.builder().build();
                var body = mapper.writeValueAsString(java.util.Map.of("query", """
                    query($keys:[String!]!) {
                      searchConditions(keywords:$keys) {
                        keywords snapshot { participantCount totalParticipantCount studyCount datasetCount unavailableFields }
                      }
                    }
                    """, "variables", java.util.Map.of("keys", java.util.List.of(
                        "PRESENT|ONESELF|MONDO:0005148", "ABSENT|ONESELF|HP:0000822"))));
                var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + app.getEnvironment().getRequiredProperty("spring.graphql.http.path")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var data = mapper.readTree(response.body());
                assertThat(data.has("errors")).as(data.toString()).isFalse();
                assertThat(data.at("/data/searchConditions/keywords").size()).isEqualTo(2);
                assertThat(data.at("/data/searchConditions/snapshot/participantCount").asInt()).isEqualTo(500);
                assertThat(data.at("/data/searchConditions/snapshot/totalParticipantCount").asInt()).isEqualTo(1000);
                assertThat(data.at("/data/searchConditions/snapshot/datasetCount").isNull()).isTrue();
            }
        } finally { backend.stop(0); }
    }
}
