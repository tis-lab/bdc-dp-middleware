package gov.nih.nhlbi.bdc.dataportal.cohortbuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BdcDpCohortBuilderApplicationTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String statusOf(String body) {
        JsonNode node = MAPPER.readTree(body);
        return node.path("status").asString();
    }

    @ParameterizedTest(name = "{0} reports UP")
    @ValueSource(strings = {
            "/",
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    })
    void endpointReportsUp(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(statusOf(response.body())).isEqualTo("UP");
    }

    @Test
    void statusEndpointReportsServiceIdentity() throws Exception {
        HttpResponse<String> response = get("/api/v1/status");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("UP");
        // Comes from spring.application.name in application.yaml.
        assertThat(body.path("service").asString())
                .isEqualTo("bdc-data-portal-cohort-builder-service");
        assertThat(body.path("message").asString())
                .isEqualTo("Starter application is running");
    }

    @Test
    void mainStartsTheApplication() {
        assertThatCode(() -> BdcDpCohortBuilderApplication.main(new String[] {
                "--spring.main.web-application-type=none",
                "--spring.main.register-shutdown-hook=false"
        })).doesNotThrowAnyException();
    }
}