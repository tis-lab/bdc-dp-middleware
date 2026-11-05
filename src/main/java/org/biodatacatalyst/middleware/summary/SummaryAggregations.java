package org.biodatacatalyst.middleware.summary;

import java.util.Map;

/** Bounded single-request aggregation. Mapper rejects omitted buckets; no partial summary is served. */
final class SummaryAggregations {
    private SummaryAggregations() {}
    static final String STUDY_FIELD = "member_of_research_study.id";
    static Map<String,Object> request() { return request(Map.of("match_all", Map.of())); }
    static Map<String,Object> request(Map<String,Object> query) {
        var parents = Map.of("reverse_nested", Map.of());
        var statuses = Map.of("terms", terms("condition.condition_status", 20), "aggs", Map.of("participants", parents));
        var relationships = Map.of("terms", terms("condition.relationship_to_participant", 20),
                "aggs", Map.of("statuses", statuses));
        var concepts = Map.of("terms", terms("condition.condition_concept", 100),
                "aggs", Map.of("participants", parents, "relationships", relationships));
        var conditions = Map.of("nested", Map.of("path", "condition"), "aggs", Map.of("concepts", concepts,
                "missing_concept", Map.of("missing", Map.of("field", "condition.condition_concept")),
                "missing_status", Map.of("missing", Map.of("field", "condition.condition_status")),
                "missing_relationship", Map.of("missing", Map.of("field", "condition.relationship_to_participant"))));
        var studies = Map.of("terms", terms(STUDY_FIELD, 100), "aggs", Map.of("conditions", conditions, "demographics", demographics()));
        return Map.of("size", 0, "track_total_hits", true, "query", query, "aggs", Map.of(
                "studies", studies,
                "missing_study", Map.of("missing", Map.of("field", STUDY_FIELD))));
    }
    private static Map<String,Object> demographics() {
        Map<String,Object> facets = new java.util.LinkedHashMap<>();
        for (String slot : java.util.List.of("sex", "race", "ethnicity")) {
            var parents = Map.of("reverse_nested", Map.of());
            facets.put(slot, Map.of("filter", Map.of("exists", Map.of("field", "demography."+slot)),
                    "aggs", Map.of("participants", parents,
                            "values", Map.of("terms", terms("demography."+slot, 100), "aggs", Map.of("participants", parents)))));
        }
        return Map.of("nested", Map.of("path", "demography"), "aggs", facets);
    }
    private static Map<String,Object> terms(String field, int size) {
        return SummaryCodeMappings.terms(field, size);
    }
}
