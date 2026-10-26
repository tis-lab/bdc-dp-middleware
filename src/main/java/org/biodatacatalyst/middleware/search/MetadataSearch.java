package org.biodatacatalyst.middleware.search;

import tools.jackson.databind.JsonNode;

/** Provider boundary. Azure AI Search needs its own adapter and response validation. */
public interface MetadataSearch {
    Execution executeProbe();

    /** Parsed backend body stays inside middleware; it is not a GraphQL result type. */
    record Execution(int httpStatus, JsonNode body, long elapsedMillis) {}
}
