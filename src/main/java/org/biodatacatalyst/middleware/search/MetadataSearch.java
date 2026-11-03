package org.biodatacatalyst.middleware.search;

import tools.jackson.databind.JsonNode;

/** Provider boundary. */
public interface MetadataSearch {
    Execution executeProbe();
    Execution executeConditions(java.util.List<String> keywords);

    default void executeCondition(String keyword) {
        executeConditions(java.util.Collections.singletonList(keyword));
    }

    /** Parsed backend body stays inside middleware; it is not a GraphQL result type. */
    record Execution(int httpStatus, JsonNode body, long elapsedMillis) {}
}
