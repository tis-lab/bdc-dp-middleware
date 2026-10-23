package org.biodatacatalyst.middleware.summary;

import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import org.biodatacatalyst.middleware.summary.SummaryModels.SummaryResponse;

public final class SummaryReader {
    private final YAMLMapper mapper = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            // Each field is read independently; document completion is checked explicitly below.
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public SummaryResponse read(InputStream input) {
        try (var parser = mapper.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid("root must be an object");
            ObjectNode summary = mapper.createObjectNode();
            Set<String> seen = new HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw invalid("expected a root property");
                String name = parser.currentName();
                if (!seen.add(name)) throw invalid("duplicate root field");
                if (parser.nextToken() == null) throw invalid("incomplete document");
                if ("records".equals(name)) {
                    parser.skipChildren();
                } else {
                    summary.set(name, mapper.readTree(parser));
                }
            }
            if (parser.nextToken() != null) throw invalid("expected exactly one YAML document");
            validateObject(summary, SummaryResponse.class, "summary");
            var response = mapper.treeToValue(summary, SummaryResponse.class);
            unique(response.terms().stream().map(t -> t.curie()).toList(), "term identifiers");
            unique(response.research_studies().stream().map(s -> s.id()).toList(), "study metadata identifiers");
            unique(response.studies().stream().map(s -> s.research_study()).toList(), "study summaries");
            Set<String> studies = new HashSet<>(response.research_studies().stream().map(s -> s.id()).toList());
            for (var study : response.studies()) if (!studies.contains(study.research_study()))
                throw invalid("study summary has no study metadata");
            return response;
        }
    }

    private static void unique(List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) throw invalid("duplicate " + field);
    }

    private static void validateObject(JsonNode node, Class<?> type, String path) {
        if (!node.isObject()) throw invalid(path + " must be an object");
        Map<String, java.lang.reflect.RecordComponent> fields = new HashMap<>();
        for (var field : type.getRecordComponents()) fields.put(field.getName(), field);
        for (var property : node.properties()) {
            if (!fields.containsKey(property.getKey())) throw invalid(path + " has an unsupported field");
        }
        Set<String> required = switch (type.getSimpleName()) {
            case "SummaryResponse" -> Set.of("release", "seed", "min_count");
            case "SummaryTerm" -> Set.of("curie");
            case "TermRelation" -> Set.of("subject", "predicate", "object", "relation_source");
            case "ResearchStudy" -> Set.of("id");
            case "StudySummary" -> Set.of("research_study", "participant_count");
            case "ParticipantSummary", "DemographySummary" -> Set.of("id", "summarized_slot", "participant_count");
            default -> Set.of("id", "participant_count");
        };
        for (var field : fields.values()) {
            String name = field.getName();
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                if (required.contains(name)) throw invalid(path + "." + name + " is required");
                continue;
            }
            String location = path + "." + name;
            if (field.getType() == List.class) {
                if (!value.isArray()) throw invalid(location + " must be an array");
                var element = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                for (var item : value) validateValue(item, element, location + "[]");
            } else validateValue(value, field.getType(), location);
        }
        if (node.hasNonNull("min_count") && node.get("min_count").bigIntegerValue().signum() < 1)
            throw invalid("min_count must be at least 1");
        if (node.hasNonNull("summarized_slot") && !Set.of("sex", "race", "ethnicity", "age_at_index").contains(node.get("summarized_slot").asString()))
            throw invalid(path + ".summarized_slot is unknown");
        if (node.hasNonNull("relation_source") && !Set.of("KG", "CURATED").contains(node.get("relation_source").asString()))
            throw invalid(path + ".relation_source is unknown");
        if (node.hasNonNull("bin_low") && node.hasNonNull("bin_high")
                && node.get("bin_low").decimalValue().compareTo(node.get("bin_high").decimalValue()) >= 0)
            throw invalid(path + " must have bin_low < bin_high");
    }

    private static void validateValue(JsonNode value, Class<?> type, String path) {
        if (value == null || value.isNull()) throw invalid(path + " must not be null");
        if (type.isRecord()) validateObject(value, type, path);
        else if (type == String.class) {
            if (!value.isString()) throw invalid(path + " must be a string");
            if (value.asString().isBlank()) throw invalid(path + " must not be blank");
        } else if (type == BigInteger.class) {
            if (!value.isIntegralNumber() || value.bigIntegerValue().signum() < 0)
                throw invalid(path + " must be a nonnegative integer");
        } else if (type == BigDecimal.class && !value.isNumber()) throw invalid(path + " must be numeric");
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid summary contract: " + message);
    }
}
