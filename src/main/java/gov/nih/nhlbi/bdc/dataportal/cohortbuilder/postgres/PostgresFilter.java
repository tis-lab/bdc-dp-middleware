package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.postgres;

import java.nio.charset.StandardCharsets;
import java.util.*;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.BAD_USER_INPUT;

/** Participant-level SQL expressions. Each event leaf uses its own correlated EXISTS. */
final class PostgresFilter {
    record Compiled(String sql, List<Object> parameters, List<String> requiredIds) {}
    private static final String PREFIX = "urn:bdc:summary:";
    static final Set<String> RELATIONS = Set.of("ONESELF", "NATURAL_PARENT", "NATURAL_FATHER",
            "NATURAL_MOTHER", "NATURAL_SIBLING", "NATURAL_BROTHER", "NATURAL_SISTER", "NATURAL_CHILD", "BLOOD_RELATIVE");
    private int nodes;

    static Compiled compile(Map<String,Object> filter) {
        return filter == null ? new Compiled("TRUE", List.of(), List.of()) : new PostgresFilter().node(filter, 1);
    }
    static List<String> focus(List<String> input) {
        if (input == null) return List.of();
        if (input.size() > 100) throw bad("focus accepts at most 100 concepts.");
        input.forEach(PostgresFilter::concept);
        return List.copyOf(new LinkedHashSet<>(input));
    }
    private Compiled node(Map<?,?> node, int depth) {
        if (depth > 8 || ++nodes > 100) throw bad("Filter exceeds 8 levels or 100 nodes.");
        if (!Set.of("condition", "summaryId", "and", "or").containsAll(node.keySet())) throw bad("Unknown filter field.");
        var fields = node.entrySet().stream().filter(e -> e.getValue() != null).map(Map.Entry::getKey).toList();
        if (fields.size() != 1) throw bad("Each filter must contain exactly one of condition, summaryId, and, or.");
        String key = (String) fields.getFirst();
        Object value = node.get(key);
        if (key.equals("condition")) {
            if (!(value instanceof Map<?,?> c)) throw bad("condition must be an object.");
            if (!Set.of("concept", "selection", "status", "relationship").containsAll(c.keySet())) throw bad("Unknown condition field.");
            if (!(c.get("concept") instanceof String curie)) throw bad("concept must be a CURIE.");
            concept(curie);
            Object selection = c.get("selection"), status = c.get("status");
            if (selection == null && status == null) throw bad("Supply selection or status.");
            String resolved = null;
            if (selection != null) {
                if (!Set.of("INCLUDE", "EXCLUDE").contains(selection)) throw bad("Invalid selection.");
                resolved = selection.equals("INCLUDE") ? "PRESENT" : "ABSENT";
            }
            if (status != null) {
                if (!Set.of("PRESENT", "ABSENT").contains(status)) throw bad("Invalid status.");
                if (resolved != null && !resolved.equals(status)) throw bad("selection and status disagree.");
                resolved = (String)status;
            }
            Object relationship = c.containsKey("relationship") ? c.get("relationship") : "ONESELF";
            if (!(relationship instanceof String rel) || !RELATIONS.contains(rel)) throw bad("Invalid relationship.");
            return leaf(id("condition", curie, rel, resolved));
        }
        if (key.equals("summaryId")) {
            if (!(value instanceof String id)) throw bad("summaryId must be a string.");
            return leaf(id);
        }
        if (!(value instanceof List<?> children) || children.isEmpty() || children.size() > 100)
            throw bad("AND/OR groups must contain between 1 and 100 filters.");
        List<String> sql = new ArrayList<>(); List<Object> args = new ArrayList<>();
        LinkedHashSet<String> required = null;
        for (Object child : children) {
            if (!(child instanceof Map<?,?> map)) throw bad("Each group member must be a filter.");
            var compiled = node(map, depth + 1);
            sql.add(compiled.sql()); args.addAll(compiled.parameters());
            if (required == null) required = new LinkedHashSet<>(compiled.requiredIds());
            else if (key.equals("and")) required.addAll(compiled.requiredIds());
            else required.retainAll(compiled.requiredIds());
        }
        return new Compiled("(" + String.join(key.equals("and") ? " AND " : " OR ", sql) + ")", List.copyOf(args), List.copyOf(required));
    }
    private static Compiled leaf(String id) {
        if (id.length() > 2048 || !id.startsWith(PREFIX)) throw bad("Invalid summary ID.");
        String decoded;
        try { decoded = new String(Base64.getUrlDecoder().decode(id.substring(PREFIX.length())), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw bad("Invalid summary ID encoding."); }
        if (!id(decoded).equals(id)) throw bad("Noncanonical summary ID.");
        String[] parts = decoded.split("\\|", -1);
        for (String part : parts) if (part.isBlank() || part.length() > 512) throw bad("Invalid summary ID component.");
        List<Object> args = new ArrayList<>(); String sql;
        if (parts[0].equals("condition") && (parts.length == 2 || parts.length == 4)) {
            sql = "EXISTS (SELECT 1 FROM counting.event e WHERE e.release=p.release AND e.ordinal=p.ordinal AND e.source_class='Condition' AND e.concept=?";
            args.add(parts[1]);
            if (parts.length == 4) { sql += " AND e.relationship=? AND e.status=?"; args.add(parts[2]); args.add(parts[3]); }
            sql += ")";
        } else if (parts[0].equals("demography") && (parts.length == 2 || parts.length == 3)
                && Set.of("sex", "race", "ethnicity").contains(parts[1])) {
            // Only these three allowlisted identifiers may enter SQL text.
            String slot = parts[1];
            sql = "EXISTS (SELECT 1 FROM counting.participant_attribute a WHERE a.release=p.release AND a.ordinal=p.ordinal AND ";
            if (parts.length == 2) sql += slot.equals("race") ? "cardinality(a.race)>0" : "a."+slot+" IS NOT NULL";
            else { sql += slot.equals("race") ? "? = ANY(a.race)" : "a."+slot+"=?"; args.add(parts[2]); }
            sql += ")";
        } else throw bad("Unsupported summary ID; only condition and demographic summaries are supported.");
        return new Compiled(sql, List.copyOf(args), List.of(id));
    }
    static String id(String... parts) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
    }
    private static void concept(String value) {
        if (value == null || value.length() > 512 || !value.matches("[A-Za-z][A-Za-z0-9._-]*:[^\\s\\p{Z}\\p{C}|*?]+"))
            throw bad("concept must be an exact CURIE without whitespace, pipes or wildcards.");
    }
    private static ApiException bad(String message) { return new ApiException(BAD_USER_INPUT, message); }
}
