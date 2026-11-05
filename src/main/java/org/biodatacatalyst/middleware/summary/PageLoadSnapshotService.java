package org.biodatacatalyst.middleware.summary;

import java.math.BigInteger;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.biodatacatalyst.middleware.search.ElasticsearchTransport;
import org.biodatacatalyst.middleware.shared.ApiException;
import tools.jackson.databind.JsonNode;
import static org.biodatacatalyst.middleware.shared.ApiException.Code.*;

/** Provisional, page-load-only contract. Counts unique indexed participant IDs, not linked persons. */
@Service
public class PageLoadSnapshotService {
    public record CountValue(BigInteger value, boolean suppressed) {}
    public record Category(String value, CountValue participantCount) {}
    public record Demographic(String slot, CountValue recordedParticipantCount,
                              CountValue missingParticipantCount, List<Category> values) {}
    public record Snapshot(String release, int minCount, String countBasis,
                           CountValue participantCount, List<Demographic> demographics) {}
    private final ElasticsearchTransport transport;
    private final SummaryProperties properties;
    private final boolean confirmed;
    public PageLoadSnapshotService(ElasticsearchTransport transport, SummaryProperties properties,
            @Value("${PAGE_LOAD_SNAPSHOT_SINGLE_PARTICIPANT_INDEX_CONFIRMED:false}") boolean confirmed) {
        this.transport=transport; this.properties=properties; this.confirmed=confirmed;
    }
    public Snapshot pageLoadSnapshot() {
        if (!confirmed) throw new ApiException(UPSTREAM_UNAVAILABLE,
            "Page-load snapshot requires confirmation of one document per participant ID in one participant index.");
        return map(transport.execute(request()).body(), properties);
    }
    static Map<String,Object> request() {
        Map<String,Object> facets=new LinkedHashMap<>();
        for (String slot : List.of("sex","race","ethnicity")) {
            var parents=Map.of("reverse_nested",Map.of());
            facets.put(slot,Map.of("filter",Map.of("exists",Map.of("field","demography."+slot)),
                "aggs",Map.of("participants",parents,"values",Map.of(
                    "terms",SummaryCodeMappings.terms("demography."+slot,100),
                    "aggs",Map.of("participants",parents)))));
        }
        return Map.of("size",0,"track_total_hits",true,"query",Map.of("match_all",Map.of()),
            "aggs",Map.of("demographics",Map.of("nested",Map.of("path","demography"),"aggs",facets)));
    }
    static Snapshot map(JsonNode body, SummaryProperties properties) {
        if (!"eq".equals(body.at("/hits/total/relation").asString())) throw invalid();
        BigInteger total=count(body.at("/hits/total/value"));
        List<Demographic> demographics=new ArrayList<>();
        for (String slot : List.of("sex","race","ethnicity")) {
            JsonNode facet=body.at("/aggregations/demographics/"+slot);
            BigInteger recorded=bounded(facet.at("/participants/doc_count"),total);
            JsonNode terms=facet.path("values");
            if (count(terms.path("sum_other_doc_count")).signum()!=0
                    || count(terms.path("doc_count_error_upper_bound")).signum()!=0
                    || !terms.path("buckets").isArray()) throw invalid();
            List<Category> values=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            for (JsonNode bucket : terms.path("buckets")) {
                JsonNode key=bucket.path("key");
                if (!key.isString() || key.asString().isBlank() || !seen.add(key.asString())) throw invalid();
                // Preserve unfamiliar indexed categories rather than silently dropping people.
                values.add(new Category(key.asString(),disclose(bounded(bucket.at("/participants/doc_count"),recorded),properties)));
            }
            values.sort(Comparator.comparing(Category::value));
            demographics.add(new Demographic(slot,disclose(recorded,properties),
                    disclose(total.subtract(recorded),properties),List.copyOf(values)));
        }
        return new Snapshot(properties.release(),properties.minCount(),"INDEXED_PARTICIPANT_ID",
                disclose(total,properties),List.copyOf(demographics));
    }
    private static CountValue disclose(BigInteger value, SummaryProperties properties) {
        boolean suppressed=value.signum()>0 && value.compareTo(BigInteger.valueOf(properties.minCount()))<0;
        return new CountValue(suppressed ? null : value,suppressed);
    }
    private static BigInteger count(JsonNode node) {
        if (!node.isIntegralNumber() || node.bigIntegerValue().signum()<0) throw invalid();
        return node.bigIntegerValue();
    }
    private static BigInteger bounded(JsonNode node, BigInteger maximum) {
        BigInteger n=count(node); if(n.compareTo(maximum)>0) throw invalid(); return n;
    }
    private static ApiException invalid() {
        return new ApiException(UPSTREAM_INVALID_RESPONSE,"Incomplete or invalid page-load snapshot aggregation.");
    }
}
