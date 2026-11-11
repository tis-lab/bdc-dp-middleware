package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.search;

import java.util.ArrayList;
import java.util.List;

/** Maps validated search results to the current API. */
final class SearchResponseMapper {
    private SearchResponseMapper() {}

    static SearchProbeResult probe(MetadataSearch.Execution execution) {
        return new SearchProbeResult("SUCCEEDED", "elasticsearch", execution.httpStatus(),
                (int) Math.min(execution.elapsedMillis(), Integer.MAX_VALUE),
                execution.body().at("/hits/total/value").bigIntegerValue().toString(),
                CohortSnapshot.counts(execution.body().at("/hits/total/value").bigIntegerValue(),
                        execution.body().at("/hits/total/value").bigIntegerValue()));
    }

    static SearchCountResult conditions(String keyword, List<String> keywords,
                                        MetadataSearch.Execution execution) {
        var counts = execution.body().at("/aggregations/counts");
        var buckets = new ArrayList<ConditionCount>();
        for (var bucket : counts.path("buckets")) {
            buckets.add(new ConditionCount(bucket.path("key").asString(),
                    bucket.path("doc_count").bigIntegerValue().toString()));
        }
        return new SearchCountResult(keyword,
                execution.body().at("/hits/total/value").bigIntegerValue().toString(),
                List.copyOf(buckets),
                counts.path("sum_other_doc_count").bigIntegerValue().toString(),
                counts.path("doc_count_error_upper_bound").bigIntegerValue().toString(),
                keywords, CohortSnapshot.counts(
                execution.body().at("/hits/total/value").bigIntegerValue(),
                execution.body().at("/aggregations/population/doc_count").bigIntegerValue()));
    }

    public record ConditionCount(String conditionKey, String documentCount) {}
    public record SearchCountResult(String keyword, String totalHits,
                                    List<ConditionCount> buckets, String sumOtherDocCount, String docCountErrorUpperBound,
                                    List<String> keywords, CohortSnapshot snapshot) {}

    public record SearchProbeResult(String status, String backend, int httpStatus, int elapsedMillis, String totalHits, CohortSnapshot snapshot) {}
}
