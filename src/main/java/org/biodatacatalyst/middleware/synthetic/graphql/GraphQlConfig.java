package org.biodatacatalyst.middleware.synthetic.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import org.springframework.context.annotation.Profile;

import java.util.Locale;

/**
 * The JSON scalar used for records returned exactly as they appear in the source YAML.
 *
 * <p>Those records have no fixed shape — they are whatever the study files hold — so they cannot
 * be described as GraphQL object types without either inventing a schema they may not follow or
 * dropping fields. A scalar keeps them intact, nulls and nested structures included.
 *
 * <p>Output only: the API is read-only, so the scalar never has to parse client input.
 */
@Profile("synthetic")
@Configuration
public class GraphQlConfig {

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
