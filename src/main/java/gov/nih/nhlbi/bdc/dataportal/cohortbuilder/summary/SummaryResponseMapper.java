package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.UPSTREAM_INVALID_RESPONSE;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryModels.*;

/** Maps counts, never adds them. Suppression is applied to every returned count. */
final class SummaryResponseMapper {
    private static final Set<String> RELATIONSHIPS = Set.of("ONESELF", "NATURAL_PARENT", "NATURAL_FATHER",
            "NATURAL_MOTHER", "NATURAL_SIBLING", "NATURAL_BROTHER", "NATURAL_SISTER", "NATURAL_CHILD", "BLOOD_RELATIVE");
    private final SummaryProperties properties;
    private final BigInteger minimum;
    SummaryResponseMapper(SummaryProperties properties) {
        this.properties = properties;
        this.minimum = BigInteger.valueOf(properties.minCount());
    }
    SummaryResponse map(JsonNode body) { return map(body, List.of()); }
    SummaryResponse map(JsonNode body, List<String> requiredIds) {
        return map(body, requiredIds, List.of());
    }
    SummaryResponse map(JsonNode body, List<String> requiredIds, List<String> focus) {
        if (count(body.at("/aggregations/missing_study/doc_count")).signum() != 0)
            throw invalid("Participants without member_of_research_study.id cannot be represented in the study-first summary. Repair study associations and reindex.");
        BigInteger matchingParticipants = count(body.at("/hits/total/value"));
        List<StudySummary> studies = new ArrayList<>();
        Set<String> terms = new TreeSet<>(focus); // Requested terms resolve even when absent or suppressed; no count is inferred.
        for (JsonNode study : buckets(body.at("/aggregations/studies"))) {
            String studyId = key(study);
            BigInteger total = bounded(study.path("doc_count"), matchingParticipants);
            if (total.compareTo(minimum) < 0) continue;
            List<ConditionSummary> conditions = new ArrayList<>();
            JsonNode conditionFacets = focus.isEmpty() ? study.path("conditions") : study.at("/conditions/selected");
            for (String field : List.of("concept", "status", "relationship"))
                if (count(conditionFacets.at("/missing_"+field+"/doc_count")).signum() != 0)
                    throw invalid("Condition records must have concept, status and relationship fields.");
            for (JsonNode concept : buckets(conditionFacets.path("concepts"))) {
                String curie = key(concept);
                if (!focus.isEmpty() && !focus.contains(curie)) throw invalid("Unexpected concept outside requested focus.");
                BigInteger conceptCount = bounded(concept.at("/participants/doc_count"), total);
                if (conceptCount.compareTo(minimum) < 0) continue;
                List<ConditionSummary> values = new ArrayList<>();
                for (JsonNode relation : buckets(concept.path("relationships"))) {
                    String relationship = key(relation);
                    if (!RELATIONSHIPS.contains(relationship)) throw invalid("Unknown relationship in condition.relationship_to_participant; inspect indexed values and the pinned BDCHM mappings.");
                    for (JsonNode status : buckets(relation.path("statuses"))) {
                        String statusName = key(status);
                        if (!Set.of("PRESENT", "ABSENT", "UNKNOWN", "HISTORICAL").contains(statusName))
                            throw invalid("Unknown condition status.");
                        BigInteger participants = bounded(status.at("/participants/doc_count"), conceptCount);
                        if (participants.compareTo(minimum) >= 0)
                            values.add(new ConditionSummary(id("condition",curie,relationship,statusName),curie,statusName,
                                    relationship,participants,null,List.of()));
                    }
                }
                terms.add(curie);
                conditions.add(new ConditionSummary(id("condition",curie),curie,null,null,conceptCount,null,values));
            }
            List<DemographySummary> demographics = new ArrayList<>();
            for (String slot : List.of("sex", "race", "ethnicity")) {
                JsonNode facet = study.at("/demographics/"+slot);
                BigInteger recorded = bounded(facet.at("/participants/doc_count"), total);
                if (recorded.compareTo(minimum) < 0) continue;
                List<DemographySummary> values = new ArrayList<>();
                for (JsonNode value : buckets(facet.path("values"))) {
                    BigInteger participants = bounded(value.at("/participants/doc_count"), recorded);
                    if (participants.compareTo(minimum) < 0) continue;
                    String name = key(value);
                    if (!demographicValues(slot).contains(name)) throw invalid("Unknown demographic value for " + slot);
                    values.add(new DemographySummary(id("demography",slot,name),slot,participants,null,
                            slot.equals("sex") ? name : null,slot.equals("race") ? List.of(name) : List.of(),
                            slot.equals("ethnicity") ? name : null,List.of()));
                }
                demographics.add(new DemographySummary(id("demography",slot),slot,recorded,null,null,List.of(),null,values));
            }
            studies.add(new StudySummary(studyId,total,ages(study.path("ages"),total),demographics,conditions,List.of(),List.of(),List.of()));
        }
        return new SummaryResponse(properties.release(), focus, requiredIds, minimum,
                terms.stream().map(c -> new SummaryTerm(c,null,null,List.of())).toList(),
                List.of(),List.of(),studies);
    }
    private List<ParticipantSummary> ages(JsonNode aggregation, BigInteger studyTotal) {
        BigInteger recorded = bounded(aggregation.path("doc_count"), studyTotal);
        JsonNode buckets = aggregation.at("/ranges/buckets");
        if (!buckets.isArray() || buckets.size() != SummaryAgeBins.BANDS.size())
            throw invalid("Age aggregation is incomplete.");
        Map<String,JsonNode> byKey = new HashMap<>();
        for (JsonNode bucket : buckets) {
            String key = key(bucket);
            if (byKey.put(key,bucket) != null) throw invalid("Duplicate age range.");
        }
        BigInteger binned = BigInteger.ZERO;
        List<ParticipantSummary> values = new ArrayList<>();
        for (var band : SummaryAgeBins.BANDS) {
            JsonNode bucket = byKey.get(band.key());
            if (bucket == null || !bucket.path("from").isNumber() || !bucket.path("to").isNumber()
                    || bucket.path("from").decimalValue().compareTo(java.math.BigDecimal.valueOf(band.fromDays())) != 0
                    || bucket.path("to").decimalValue().compareTo(java.math.BigDecimal.valueOf(band.toDays())) != 0)
                throw invalid("Unexpected age range bounds.");
            BigInteger participants = bounded(bucket.path("doc_count"),recorded);
            binned = binned.add(participants);
            if (participants.compareTo(minimum) >= 0)
                values.add(new ParticipantSummary(id("participant","age_at_index",band.key(),"a"),
                        "age_at_index",participants,null,java.math.BigDecimal.valueOf(band.low()),
                        java.math.BigDecimal.valueOf(band.high()),"a",List.of()));
        }
        if (binned.compareTo(recorded) > 0) throw invalid("Age bin counts exceed recorded participants.");
        if (recorded.compareTo(minimum) < 0) return List.of();
        return List.of(new ParticipantSummary(id("participant","age_at_index"),"age_at_index",
                recorded,null,null,null,null,values));
    }

    // Values pinned by synthetic/bdchm.yaml; schema validation also checks emitted payloads in CI.
    private static Set<String> demographicValues(String slot) {
        return switch (slot) {
            case "sex" -> Set.of("MALE", "FEMALE", "UNKNOWN");
            case "ethnicity" -> Set.of("HISPANIC_OR_LATINO", "NOT_HISPANIC_OR_LATINO");
            case "race" -> Set.of("AMERICAN_INDIAN_OR_ALASKA_NATIVE", "ASIAN", "BLACK_OR_AFRICAN_AMERICAN",
                    "MIDDLE_EASTERN_OR_NORTH_AFRICAN", "NATIVE_HAWAIIAN_OR_OTHER_PACIFIC_ISLANDER", "UNKNOWN", "WHITE", "MORE_THAN_ONE_RACE");
            default -> throw invalid("Unknown demographic slot.");
        };
    }
    private static Iterable<JsonNode> buckets(JsonNode aggregation) {
        if (!aggregation.path("buckets").isArray()
                || count(aggregation.path("sum_other_doc_count")).signum() != 0
                || count(aggregation.path("doc_count_error_upper_bound")).signum() != 0)
            throw invalid("Aggregation is incomplete; reduce scope or implement bucket pagination.");
        return aggregation.path("buckets");
    }
    private static BigInteger count(JsonNode node) {
        if (!node.isIntegralNumber() || node.bigIntegerValue().signum() < 0) throw invalid("Invalid count.");
        return node.bigIntegerValue();
    }
    private static BigInteger bounded(JsonNode node, BigInteger upper) {
        var value = count(node);
        if (value.compareTo(upper) > 0) throw invalid("Participant count exceeds its parent population.");
        return value;
    }
    private static String key(JsonNode bucket) {
        if (!bucket.path("key").isString() || bucket.path("key").asString().isBlank()) throw invalid("Invalid bucket key.");
        return bucket.path("key").asString();
    }
    private static String id(String... parts) {
        return "urn:bdc:summary:"+ Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join("|",parts).getBytes(StandardCharsets.UTF_8));
    }
    private static ApiException invalid(String message) { return new ApiException(UPSTREAM_INVALID_RESPONSE,message); }
}

