package org.biodatacatalyst.middleware.terms;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.biodatacatalyst.middleware.DataPortalMiddlewareApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class AutocompleteGraphQlTest {
    @Test
    void exposesDescriptionAndCoverageThroughHttpGraphQl() throws Exception {
        var body = new AtomicReference<>("""
            {"items":[{"id":"HP:0000822","name":"Hypertension",
             "category":"biolink:PhenotypicFeature","description":"Upstream description"}],"total":1}
            """);
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/autocomplete", exchange -> {
            var bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        try (var app = SpringApplication.run(DataPortalMiddlewareApplication.class,
                "--server.port=0", "--spring.profiles.active=", "--harmonized.enabled=false",
                "--app.data-location=file:/nonexistent/synthetic/",
                "--monarch.base-url=http://127.0.0.1:" + upstream.getAddress().getPort())) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            var json = JsonMapper.builder().build();
            var payload = json.writeValueAsString(Map.of("query", """
                { autocomplete(query: "Hyper") {
                    total items { id description bdc { studyCount studies variableNames } }
                } }
                """));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            try (var http = HttpClient.newHttpClient()) {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var root = json.readTree(response.body());
                assertThat(root.has("errors")).as(response.body()).isFalse();
                var item = root.path("data").path("autocomplete").path("items").get(0);
                assertThat(item.path("description").asString()).isEqualTo("Upstream description");
                assertThat(item.path("bdc").path("studyCount").asInt()).isZero();

                assertThat(item.path("bdc").path("variableNames")).isEmpty();
                body.set("{\"items\":[{\"id\":\"HP:0000822\",\"name\":\"Hypertension\",\"category\":\"biolink:PhenotypicFeature\"}],\"total\":1}");
                root = json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
                assertThat(root.has("errors")).isFalse();
                assertThat(root.path("data").path("autocomplete").path("items").get(0).path("description").isNull()).isTrue();
            }
        } finally { upstream.stop(0); }
    }
}
