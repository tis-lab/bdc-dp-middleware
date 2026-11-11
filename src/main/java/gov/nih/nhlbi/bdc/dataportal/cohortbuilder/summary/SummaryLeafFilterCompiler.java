package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.nio.charset.StandardCharsets;
import java.util.*;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.BAD_USER_INPUT;

/** Supported leaf predicates only. Extend the allowlist, dispatcher and SDL together.
 * Group recursion and complexity limits belong to SummaryFilterCompiler.
 * Future temporal/event predicates need explicit event scope; do not flatten them into participant AND.
 */
final class SummaryLeafFilterCompiler {
    private static final String PREFIX = "urn:bdc:summary:";
    static final Set<String> FIELDS = Set.of("condition", "summaryId");
    private SummaryLeafFilterCompiler() {}

    static SummaryFilterCompiler.Compiled compile(String field, Object value) {
        return switch (field) {
            case "condition" -> {
                if (!(value instanceof Map<?,?> condition)) throw bad("condition must be an object.");
                yield condition(condition);
            }
            case "summaryId" -> {
                if (!(value instanceof String id)) throw bad("summaryId must be a string.");
                yield new SummaryFilterCompiler.Compiled(leaf(id), List.of(id));
            }
            default -> throw bad("Unsupported criterion: " + field);
        };
    }
    private static SummaryFilterCompiler.Compiled condition(Map<?,?> condition) {
        if (!Set.of("concept", "selection", "status", "relationship").containsAll(condition.keySet()))
            throw bad("Unknown condition field.");
        if (!(condition.get("concept") instanceof String concept)
                || concept.length() > 512
                || !concept.matches(
                "[A-Za-z][A-Za-z0-9._-]*:[^\\s\\p{Z}\\p{C}|*?]+")) {
            throw bad(
                    "concept must be a CURIE in prefix:identifier format, "
                            + "without whitespace, control characters, pipes, or wildcards; "
                            + "for example MONDO:0005044.");
        }
        Object selection = condition.get("selection");
        Object suppliedStatus = condition.get("status");
        if (selection == null && suppliedStatus == null)
            throw bad("Supply condition.selection (INCLUDE/EXCLUDE) or condition.status (PRESENT/ABSENT).");
        String status = null;
        if (selection != null) {
            if (!Set.of("INCLUDE", "EXCLUDE").contains(selection)) throw bad("Invalid condition selection.");
            status = selection.equals("INCLUDE") ? "PRESENT" : "ABSENT";
        }
        if (suppliedStatus != null) {
            if (!Set.of("PRESENT", "ABSENT").contains(suppliedStatus)) throw bad("Invalid condition status.");
            if (status != null && !status.equals(suppliedStatus)) throw bad("selection and status disagree.");
            status = (String)suppliedStatus;
        }
        Object relationship = condition.containsKey("relationship") ? condition.get("relationship") : "ONESELF";
        if (!(relationship instanceof String relation) || !Set.of("ONESELF", "NATURAL_PARENT", "NATURAL_FATHER",
                "NATURAL_MOTHER", "NATURAL_SIBLING", "NATURAL_BROTHER", "NATURAL_SISTER", "NATURAL_CHILD", "BLOOD_RELATIVE").contains(relation))
            throw bad("Invalid relationship; omit it to use ONESELF.");
        // Keep response IDs compatible with the existing summary.yaml response contract.
        String id = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.join("|", "condition", concept, relation, status).getBytes(StandardCharsets.UTF_8));
        return new SummaryFilterCompiler.Compiled(leaf(id), List.of(id));
    }
    private static Map<String,Object> leaf(String id) {
        if (id.length() > 2048 || !id.startsWith(PREFIX)) throw bad("Invalid summary ID; use an ID returned by summary.");
        String decoded;
        try { decoded = new String(Base64.getUrlDecoder().decode(id.substring(PREFIX.length())), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw bad("Invalid summary ID encoding."); }
        String canonical = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.getBytes(StandardCharsets.UTF_8));
        if (!canonical.equals(id)) throw bad("Noncanonical summary ID.");
        String[] parts = decoded.split("\\|", -1);
        for (String part : parts) if (part.isBlank() || part.length() > 512) throw bad("Invalid summary ID component.");
        var clauses = new ArrayList<Map<String,Object>>();
        String path;
        if (parts[0].equals("condition") && (parts.length == 2 || parts.length == 4)) {
            path = "condition";
            clauses.add(term("condition.condition_concept", parts[1]));
            if (parts.length == 4) {
                clauses.add(term("condition.relationship_to_participant", parts[2]));
                clauses.add(term("condition.condition_status", parts[3]));
            }
        } else if (parts[0].equals("demography") && (parts.length == 2 || parts.length == 3)
                && Set.of("sex", "race", "ethnicity").contains(parts[1])) {
            path = "demography";
            String field = path + "." + parts[1];
            clauses.add(parts.length == 2 ? Map.of("exists", Map.of("field", field)) : term(field, parts[2]));
        } else throw bad("Unsupported summary ID; only condition and demographic summaries are supported.");
        // All qualifiers of one condition value must match the SAME nested record.
        return Map.of("nested", Map.of("path", path, "score_mode", "none", "query", Map.of("bool", Map.of("filter", clauses))));
    }
    private static Map<String,Object> term(String field, String value) { return SummaryCodeMappings.predicate(field, value); }
    private static ApiException bad(String message) { return new ApiException(BAD_USER_INPUT, message); }
}
