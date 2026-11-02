package org.biodatacatalyst.middleware.summary;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.schema.*;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration
public class SummaryScalarConfiguration {
    @Bean RuntimeWiringConfigurer summaryScalars() {
        return builder -> builder.scalar(number("Count", BigInteger.class)).scalar(number("Decimal", BigDecimal.class));
    }
    private static GraphQLScalarType number(String name, Class<?> type) {
        return GraphQLScalarType.newScalar().name(name).coercing(new Coercing<Object, Object>() {
            @Override public Object serialize(@NonNull Object value, @NonNull GraphQLContext context, @NonNull Locale locale) {
                if (!type.isInstance(value)) throw new CoercingSerializeException("Invalid " + name + " value");
                return value;
            }
            @Override public Object parseValue(@NonNull Object input, @NonNull GraphQLContext context, @NonNull Locale locale) {
                throw new CoercingParseValueException(name + " is output-only");
            }
            @Override public Object parseLiteral(@NonNull Value<?> input, @NonNull CoercedVariables variables,
                                                 @NonNull GraphQLContext context, @NonNull Locale locale) {
                throw new CoercingParseLiteralException(name + " is output-only");
            }
        }).build();
    }
}
