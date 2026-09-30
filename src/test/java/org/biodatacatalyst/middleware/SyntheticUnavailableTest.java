package org.biodatacatalyst.middleware;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.biodatacatalyst.middleware.monarch.MonarchClient;
import org.biodatacatalyst.middleware.terms.TermResults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The synthetic profile is active but the data directory does not exist.
 *
 * <p>This is the {@code @Lazy} guarantee: {@code StudyRepository} is only constructed by the first
 * query that actually needs study records, so the application starts, the probes pass, and term
 * resolution works even when the YAML is missing or broken. Study data can never take down the
 * feature this service exists for.
 */
@ActiveProfiles("synthetic")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.data-location=classpath:no-such-data/")
class SyntheticUnavailableTest {

    @Autowired
    TestRestTemplate http;

    @MockitoBean
    MonarchClient monarch;

    @Test
    void termsAndHealthDoNotTriggerYamlLoadingEvenWhenFeatureIsEnabled() {
        when(monarch.search("Hypertension", 20, 0)).thenReturn(new TermResults(List.of(), 0, 20, 0, false));

        // definitions is included on purpose: its rules are hard-coded code lists, so the metadata
        // must resolve without the repository ever being constructed.
        var document = """
            { health { status }
              terms(query:"Hypertension") { total }
              definitions { definitionId } }
            """;

        JsonNode result = http.postForObject("/graphql", Map.of("query", document), JsonNode.class);

        assertThat(result.has("errors")).as(result.toString()).isFalse();
        assertThat(result.at("/data/health/status").asText()).isEqualTo("UP");
        assertThat(result.at("/data/terms/total").asInt()).isZero();
        assertThat(result.at("/data/definitions").isArray()).isTrue();
        assertThat(result.at("/data/definitions")).isNotEmpty();
    }
}
