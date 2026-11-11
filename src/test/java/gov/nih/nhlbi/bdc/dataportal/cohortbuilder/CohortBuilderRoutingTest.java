package gov.nih.nhlbi.bdc.dataportal.cohortbuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class CohortBuilderRoutingTest {
    @Test
    void servesQueriesUnderCbAndKeepsProbePathsUnchanged() throws Exception {
        try (var app = SpringApplication.run(BdcDpCohortBuilderApplication.class,
                "--server.port=0", "--harmonized.enabled=false", "--search.enabled=false",
                "--spring.graphql.graphiql.enabled=true");
             var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:"
                    + Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort();
            var mapper = JsonMapper.builder().build();
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/cb/query"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ health { status } }\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var result = mapper.readTree(response.body());
            assertThat(result.has("errors")).isFalse();
            assertThat(result.at("/data/health/status").asString()).isEqualTo("UP");

            for (String path : new String[]{"/graphql", "/query", "/cb/graphql"}) {
                var old = client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ health { status } }\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(old.statusCode()).as(path).isEqualTo(404);
            }
            for (String path : new String[]{"/actuator/health/liveness", "/actuator/health/readiness"}) {
                var probe = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(probe.statusCode()).as(path).isEqualTo(200);
                assertThat(mapper.readTree(probe.body()).path("status").asString()).isEqualTo("UP");
            }
            var console = client.send(HttpRequest.newBuilder(URI.create(base + "/cb/graphiql"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(console.statusCode()).isEqualTo(307);
            URI consoleLocation = URI.create(base).resolve(console.headers().firstValue("Location").orElseThrow());
            assertThat(consoleLocation.getPath()).isEqualTo("/cb/graphiql");
            assertThat(consoleLocation.getQuery()).contains("path=/cb/query");
            console = client.send(HttpRequest.newBuilder(consoleLocation).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(console.statusCode()).isEqualTo(200);
            assertThat(console.body()).contains("GraphiQL");
        }
    }
}
