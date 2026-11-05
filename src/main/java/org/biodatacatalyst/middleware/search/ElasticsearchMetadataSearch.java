package org.biodatacatalyst.middleware.search;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import org.biodatacatalyst.middleware.shared.ApiException;
import static org.biodatacatalyst.middleware.shared.ApiException.Code.*;

final class ElasticsearchMetadataSearch implements MetadataSearch {
    private final ElasticsearchTransport transport;
    ElasticsearchMetadataSearch(SearchProperties properties) { this(new ElasticsearchTransport(properties)); }
    ElasticsearchMetadataSearch(ElasticsearchTransport transport) { this.transport = transport; }
    private Execution execute(Map<String,Object> body) { return transport.execute(body); }
    @Override public Execution executeProbe() {
        return execute(Map.of("size", 0, "track_total_hits", true,
                "query", Map.of("match_all", Map.of())));
    }

    @Override public Execution executeConditions(java.util.List<String> keywords) {
        var selection = new ConditionSelection(keywords);
        var result = execute(Map.of("size", 0, "track_total_hits", true,
                "query", selection.query(),
                "aggs", Map.of(
                        "population", Map.of("global", Map.of()),
                        "counts", Map.of("terms", Map.of("field", "_conditions", "size", 100,
                                "exclude", selection.keywords())))));
        JsonNode population = result.body().at("/aggregations/population/doc_count");
        nonnegative(population);
        if (population.bigIntegerValue().compareTo(result.body().at("/hits/total/value").bigIntegerValue()) < 0)
            throw invalidResponse();
        JsonNode counts = result.body().at("/aggregations/counts");
        if (!counts.isObject() || !counts.path("buckets").isArray()
                || counts.path("buckets").size() > 100) throw invalidResponse();
        nonnegative(counts.path("sum_other_doc_count"));
        nonnegative(counts.path("doc_count_error_upper_bound"));
        for (JsonNode bucket : counts.path("buckets")) {
            if (!bucket.path("key").isString() || bucket.path("key").asString().isBlank())
                throw invalidResponse();
            nonnegative(bucket.path("doc_count"));
        }
        return result;
    }

    private static void nonnegative(JsonNode value) {
        if (!value.isIntegralNumber() || value.bigIntegerValue().signum() < 0)
            throw invalidResponse();
    }

    private static ApiException invalidResponse() {
        return new ApiException(UPSTREAM_INVALID_RESPONSE, "Metadata search returned an invalid or unexpected response.");
    }
}
