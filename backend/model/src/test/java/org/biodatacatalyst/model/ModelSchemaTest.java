package org.biodatacatalyst.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.biodatacatalyst.model.types.Item;
import org.biodatacatalyst.model.types.PageInfo;
import org.biodatacatalyst.model.types.SearchQuery;
import org.biodatacatalyst.model.types.SearchResult;
import org.biodatacatalyst.model.types.SortDirection;
import org.junit.jupiter.api.Test;

class ModelSchemaTest {

    /** Stands in for the app's API schema, which owns the root types. */
    private static final String STUB_API = "type Query { search(query: SearchQuery!): SearchResult! }";

    @Test
    void schemaFilesMergeIntoValidSchema() throws IOException {
        SchemaParser parser = new SchemaParser();
        TypeDefinitionRegistry registry = new TypeDefinitionRegistry();
        List<Path> files = schemaFiles();
        assertEquals(List.of("common.graphqls", "query.graphqls", "response.graphqls"),
                files.stream().map(p -> p.getFileName().toString()).toList());
        for (Path file : files) {
            registry.merge(parser.parse(file.toFile()));
        }
        registry.merge(parser.parse(STUB_API));

        GraphQLSchema schema = new SchemaGenerator()
                .makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());

        assertNotNull(schema.getType("SearchQuery"));
        assertNotNull(schema.getType("SearchResult"));
    }

    @Test
    void generatedTypesAreUsable() {
        SearchQuery query = SearchQuery.newBuilder().text("abc").build();
        SearchResult result = SearchResult.newBuilder()
                .items(List.of(new Item("1", "first")))
                .page(PageInfo.newBuilder().limit(20).offset(0).total(1).hasMore(false).build())
                .build();

        assertEquals("abc", query.getText());
        assertEquals("first", result.getItems().getFirst().getName());
        assertTrue(List.of(SortDirection.values()).contains(SortDirection.ASC));
    }

    private static List<Path> schemaFiles() throws IOException {
        Path dir = Path.of(System.getProperty("model.schema.dir"));
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.toString().endsWith(".graphqls")).sorted().toList();
        }
    }
}
