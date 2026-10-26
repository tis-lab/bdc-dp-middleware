package org.biodatacatalyst.middleware.search;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.biodatacatalyst.middleware.DataPortalMiddlewareApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SearchProbeGraphQlTest {
    private JsonNode call(int port) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"query":"{ searchProbe { status backend httpStatus elapsedMillis } }"}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
            return JsonMapper.builder().build().readTree(response.body());
        }
    }
    @Test void bootsWithSearchDisabledAndReportsClearError() throws Exception {
        try (var app = SpringApplication.run(DataPortalMiddlewareApplication.class,
                "--server.port=0", "--harmonized.enabled=false", "--search.enabled=false")) {
            var result = call(Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort());
            assertThat(result.at("/errors/0/extensions/code").asString()).isEqualTo("UPSTREAM_UNAVAILABLE");
        }
    }
    @Test void executesThroughGraphQlAndReturnsDiagnosticsOnly() throws Exception {
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = ElasticsearchMetadataSearchTest.OK.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        backend.start();
        try (var app = SpringApplication.run(DataPortalMiddlewareApplication.class,
                "--server.port=0", "--harmonized.enabled=false", "--search.enabled=true",
                "--search.endpoint=http://127.0.0.1:" + backend.getAddress().getPort(),
                "--search.index=metadata-test")) {
            var result = call(Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer()).getPort());
            assertThat(result.has("errors")).as(result.toString()).isFalse();
            assertThat(result.at("/data/searchProbe/status").asString()).isEqualTo("SUCCEEDED");
            assertThat(result.at("/data/searchProbe/httpStatus").asInt()).isEqualTo(200);
            assertThat(result.at("/data/searchProbe").size()).isEqualTo(4);
        } finally { backend.stop(0); }
    }
}
