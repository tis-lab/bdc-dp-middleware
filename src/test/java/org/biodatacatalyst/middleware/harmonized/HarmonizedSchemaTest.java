package org.biodatacatalyst.middleware.harmonized;

import graphql.GraphQL;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercise the actual SDL and enum serialization without a network or Spring context. */
class HarmonizedSchemaTest {
    @Test
    void exposesTypedCategoriesAndSafeConceptLabels() throws Exception {
        var concept = new BdcConcept("EXAMPLE:1", null, BdchmTypeEnum.condition,
                List.of("Condition"), List.of(), List.of("TEST"), List.of());
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/graphql/core/schema.graphqls")), StandardCharsets.UTF_8)) {
            var registry = new SchemaParser().parse(reader);
            var wiring = RuntimeWiring.newRuntimeWiring()
                    .type("Query", type -> type.dataFetcher("terms",
                            environment -> Map.of("items", List.of(concept))))
                    .type("SearchItem", type -> type.typeResolver(
                            env -> env.getSchema().getObjectType("BdcConcept")))
                    .build();
            var graphql = GraphQL.newGraphQL(new SchemaGenerator()
                    .makeExecutableSchema(registry, wiring)).build();
            var result = graphql.execute("""
                { terms(query: "EXAMPLE:1") {
                    items { ... on BdcConcept { conceptType conceptLabel displayLabel bdchmType bdchmClasses } }
                } }
                """);
            assertThat(result.getErrors()).isEmpty();
            Map<String, Object> data = result.getData();
            assert data != null;
            @SuppressWarnings("unchecked")
            var terms = (Map<String, Object>) data.get("terms");
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) terms.get("items");
            assertThat(items.getFirst()).containsEntry("bdchmType", "condition")
                    .containsEntry("conceptLabel", null).containsEntry("displayLabel", "EXAMPLE:1")
                    .containsEntry("bdchmClasses", List.of("Condition"));
            assertThat(graphql.getGraphQLSchema().getType("BdchmTypeEnum"))
                    .isInstanceOf(graphql.schema.GraphQLEnumType.class);
        }
    }
}
