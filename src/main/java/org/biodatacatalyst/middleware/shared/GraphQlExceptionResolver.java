package org.biodatacatalyst.middleware.shared;

import java.util.Map;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;


@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final Logger log = LoggerFactory.getLogger(GraphQlExceptionResolver.class);

    @Override
    protected GraphQLError resolveToSingleError(@NonNull Throwable exception, DataFetchingEnvironment environment) {
        String field = environment.getField().getName();

        String code = "INTERNAL_ERROR";
        String message = "The request could not be completed.";
        ErrorType type = ErrorType.INTERNAL_ERROR;

        if (exception instanceof ApiException api) {
            code = api.code().name();
            message = api.getMessage();
            if (api.code() == ApiException.Code.BAD_USER_INPUT) {
                type = ErrorType.BAD_REQUEST;
            }
            log.debug("{} on field {}: {}", code, field, message);

        } else if (exception instanceof ResponseStatusException status) {
            if (status.getStatusCode().value() == 404) {
                code = "NOT_FOUND";
                message = "Requested synthetic record was not found.";
                type = ErrorType.NOT_FOUND;
            } else if (status.getStatusCode().value() == 400) {
                code = "BAD_USER_INPUT";
                message = "Invalid synthetic query arguments.";
                type = ErrorType.BAD_REQUEST;
            } else {
                log.error("Unexpected status {} on field {}", status.getStatusCode(), field, status);
            }
            log.debug("{} on field {}", code, field);

        } else {
            log.error("Unhandled exception on field {}", field, exception);
        }

        return GraphqlErrorBuilder.newError(environment)
                .message(message)
                .errorType(type)
                .extensions(Map.of("code", code))
                .build();
    }
}
