package org.biodatacatalyst.middleware.search;

import java.util.ArrayList;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** Initial query execution endpoints; no summary-schema mapping. */
@Controller
public class SearchProbeController {
    private final MetadataSearch search;

    public SearchProbeController(MetadataSearch search) {
        this.search = search;
    }

    @QueryMapping
    public SearchProbeResult searchProbe() {
        var execution = search.executeProbe();
        return new SearchProbeResult("SUCCEEDED", "elasticsearch", execution.httpStatus(),
                (int) Math.min(execution.elapsedMillis(), Integer.MAX_VALUE),
                execution.body().at("/hits/total/value").bigIntegerValue().toString());
    }

    @QueryMapping
    public SearchCountResult searchConditions(@Argument("keyword") String keyword) {
        var execution = search.executeCondition(keyword);
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
                counts.path("doc_count_error_upper_bound").bigIntegerValue().toString());
    }

    public record ConditionCount(String conditionKey, String documentCount) {}
    public record SearchCountResult(String keyword, String totalHits,
                                    List<ConditionCount> buckets, String sumOtherDocCount, String docCountErrorUpperBound) {}

    public record SearchProbeResult(String status, String backend, int httpStatus, int elapsedMillis, String totalHits) {}
}
