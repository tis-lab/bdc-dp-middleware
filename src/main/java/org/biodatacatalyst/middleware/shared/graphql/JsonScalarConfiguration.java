package org.biodatacatalyst.middleware.shared.graphql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.util.Locale;

@Configuration
public class JsonScalarConfiguration {

    @Bean
    public RuntimeWiringConfigurer jsonScalarConfigurer(ObjectMapper mapper) {
        return wiringBuilder -> wiringBuilder.scalar(jsonScalar(mapper));
    }

    private static GraphQLScalarType jsonScalar(ObjectMapper mapper) {
        return GraphQLScalarType.newScalar()
                .name("JSON")
                .description("An arbitrary JSON value, returned unchanged.")
                .coercing(new Coercing<>() {

                    @Override
                    public Object serialize(@NonNull Object dataFetcherResult, @NonNull GraphQLContext context, @NonNull Locale locale) {
                        // Jackson nodes are converted to plain maps and lists; everything else is
                        // already a Map, List, String, Number, Boolean or null.
                        return dataFetcherResult instanceof JsonNode node
                                ? mapper.convertValue(node, Object.class)
                                : dataFetcherResult;
                    }

                    @Override
                    public Object parseValue(@NonNull Object input, @NonNull GraphQLContext context, @NonNull Locale locale) {
                        throw new graphql.schema.CoercingParseValueException("JSON is output-only");
                    }

                    @Override
                    public Object parseLiteral(@NonNull Value<?> input, @NonNull CoercedVariables variables,
                                               @NonNull GraphQLContext context, @NonNull Locale locale) {
                        throw new graphql.schema.CoercingParseLiteralException("JSON is output-only");
                    }
                })
                .build();
    }
}
