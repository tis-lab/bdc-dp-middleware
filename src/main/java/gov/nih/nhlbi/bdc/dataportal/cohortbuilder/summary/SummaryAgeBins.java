package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Stable bands from ES-EXAMPLES.md; bounds are years, indexed ages are integer days. */
final class SummaryAgeBins {
    private SummaryAgeBins() {}
    record Band(String key, int low, int high) {
        int fromDays() { return days(low); }
        int toDays() { return days(high); }
    }
    static final List<Band> BANDS = List.of(new Band("45-55",45,55),
            new Band("55-65",55,65), new Band("65-80",65,80));
    static int days(int years) {
        return BigDecimal.valueOf(years).multiply(new BigDecimal("365.25"))
                .setScale(0,RoundingMode.CEILING).intValueExact();
    }
    static Map<String,Object> aggregation() {
        var ranges=BANDS.stream().map(b -> Map.<String,Object>of(
                "key",b.key(),"from",b.fromDays(),"to",b.toDays())).toList();
        return Map.of("filter",Map.of("exists",Map.of("field","age_at_index")),
                "aggs",Map.of("ranges",Map.of("range",Map.of(
                        "field","age_at_index","keyed",false,"ranges",ranges))));
    }
}
