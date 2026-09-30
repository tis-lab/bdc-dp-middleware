package org.biodatacatalyst.middleware;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.biodatacatalyst.middleware.monarch.MonarchClient;
import org.biodatacatalyst.middleware.shared.ApiException;
import org.biodatacatalyst.middleware.synthetic.service.StudyRepository;
import org.biodatacatalyst.middleware.terms.TermResults;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.profiles.active=", "app.data-location=classpath:no-such-data/", "spring.graphql.graphiql.enabled=true"})
class SyntheticDisabledTest {
    @Autowired ApplicationContext context;
    @Autowired GraphQlSource source;
    @Autowired TestRestTemplate http;
    @MockitoBean MonarchClient monarch;
    @Test void startsWithoutYamlAndServesTypedTermsAndHealth() {
        assertThat(context.getBeansOfType(StudyRepository.class)).isEmpty();
        assertThat(source.schema().getQueryType().getFieldDefinition("studies")).isNull();
        when(monarch.search("Hypertension",20,0)).thenReturn(new TermResults(List.of(
                new TermResults.Term("HP:0000822","Hypertension","biolink:PhenotypicFeature",null,List.of(),"Hypertension (HP:0000822)")),1,20,0,false));
        JsonNode result = query("""
            { health { status } terms(query: "Hypertension") { total items { id label description synonyms displayLabel } } }
            """);
        assertThat(result.has("errors")).isFalse();
        assertThat(result.at("/data/terms/items/0/id").asText()).isEqualTo("HP:0000822");
        assertThat(result.at("/data/terms/items/0/description").isNull()).isTrue();
        assertThat(result.at("/data/terms/items/0/displayLabel").asText()).isEqualTo("Hypertension (HP:0000822)");
        assertThat(result.at("/data/health/status").asText()).isEqualTo("UP");
        assertThat(http.getForEntity("/actuator/health/liveness",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/actuator/health/readiness",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/graphiql",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/api/v1/studies",String.class).getStatusCode().value()).isEqualTo(404);
    }
    @Test void returnsErrorCodesAndPreservesIndependentFields() {
        when(monarch.search("Hypertension",20,0)).thenThrow(new ApiException(ApiException.Code.UPSTREAM_UNAVAILABLE,"Monarch is currently unavailable."));
        JsonNode result = query("""
            { health { status } terms(query: "Hypertension") { total } }
            """);
        assertThat(result.at("/errors/0/extensions/code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(result.at("/data/terms").isNull()).isTrue();
        assertThat(result.at("/data/health/status").asText()).isEqualTo("UP");
        result = query("""
            { terms(query: " ") { total } }
            """);
        assertThat(result.at("/errors/0/extensions/code").asText()).isEqualTo("BAD_USER_INPUT");
    }
    private JsonNode query(String document) {
        var response = http.postForEntity("/graphql",Map.of("query",document),JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }
}
