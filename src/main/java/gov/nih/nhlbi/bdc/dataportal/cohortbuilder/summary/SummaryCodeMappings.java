package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.util.*;

/** Exact enum meanings from pinned synthetic/bdchm.yaml; not clinical inference.
 * Normalize before bucketing so mixed label/CURIE observations retain distinct participant counts.
 */
final class SummaryCodeMappings {
    private SummaryCodeMappings() {}
    private static final Map<String,Map<String,String>> MEANINGS = Map.of(
        "condition.relationship_to_participant", Map.of(
            "NATURAL_PARENT", "OMOP:4029630", "NATURAL_FATHER", "OMOP:4321888",
            "NATURAL_MOTHER", "OMOP:4277283", "NATURAL_SIBLING", "OMOP:4218412",
            "NATURAL_BROTHER", "OMOP:4263682", "NATURAL_SISTER", "OMOP:4251326",
            "NATURAL_CHILD", "OMOP:4326600", "BLOOD_RELATIVE", "OMOP:4053608"),
        "demography.sex", Map.of("MALE", "OMOP:8507", "FEMALE", "OMOP:8532", "UNKNOWN", "OMOP:8552"),
        "demography.ethnicity", Map.of("HISPANIC_OR_LATINO", "OMOP:38003563", "NOT_HISPANIC_OR_LATINO", "OMOP:38003564"),
        "demography.race", Map.of(
            "AMERICAN_INDIAN_OR_ALASKA_NATIVE", "OMOP:8657", "ASIAN", "OMOP:8515",
            "BLACK_OR_AFRICAN_AMERICAN", "OMOP:8516", "MIDDLE_EASTERN_OR_NORTH_AFRICAN", "OMOP:38003615",
            "NATIVE_HAWAIIAN_OR_OTHER_PACIFIC_ISLANDER", "OMOP:8557", "UNKNOWN", "OMOP:8552",
            "WHITE", "OMOP:8527", "MORE_THAN_ONE_RACE", "OMOP:45880900"));

    static Map<String,Object> predicate(String field, String value) {
        var meanings = MEANINGS.getOrDefault(field, Map.of());
        String label = value;
        for (var entry : meanings.entrySet())
            if (entry.getValue().equals(value)) label = entry.getKey();
        String meaning = meanings.get(label);
        return meaning == null ? Map.of("term", Map.of(field, value))
                : Map.of("terms", Map.of(field, List.of(label, meaning)));
    }

    static Map<String,Object> terms(String field, int size) {
        var meanings = MEANINGS.get(field);
        if (meanings == null) return Map.of("field", field, "size", size, "show_term_doc_count_error", true);
        var aliases = new TreeMap<String,String>();
        meanings.forEach((label, meaning) -> aliases.put(meaning, label));
        // Return each normalized keyword before terms aggregation, not after counting raw buckets.
        // Unrecognized codes remain visible for the response mapper to reject.
        String source = "def values = new ArrayList(); for (def value : doc[params.field]) { "
                + "values.add(params.aliases.containsKey(value) ? params.aliases[value] : value); } return values;";
        return Map.of("script", Map.of("lang", "painless", "source", source,
                        "params", Map.of("field", field, "aliases", aliases)),
                "size", size, "show_term_doc_count_error", true);
    }
}
