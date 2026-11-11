package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.util.*;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.BAD_USER_INPUT;

/** Stable participant-level AND/OR composition, complexity limits and mandatory-ID propagation.
 * Leaves are compiled independently; adding a supported criterion must not alter existing group semantics.
 * Never accepts arbitrary Elasticsearch fields or DSL.
 */
public final class SummaryFilterCompiler {
    public record Compiled(Map<String,Object> query, List<String> requiredIds) {}
    private int nodes;
    public static Compiled compile(Map<String,Object> filter) {
        return filter == null ? new Compiled(Map.of("match_all", Map.of()), List.of())
                : new SummaryFilterCompiler().node(filter, 1);
    }
    private Compiled node(Map<?,?> input, int depth) {
        if (depth > 8 || ++nodes > 100) throw bad("Filter exceeds 8 levels or 100 nodes.");
        if (input.keySet().stream().anyMatch(key -> !Set.of("and", "or").contains(key)
                && !SummaryLeafFilterCompiler.FIELDS.contains(key))) throw bad("Unknown filter field.");
        var keys = input.entrySet().stream().filter(e -> e.getValue() != null).map(Map.Entry::getKey).toList();
        if (keys.size() != 1) throw bad("Each filter must contain exactly one of condition, summaryId, and, or.");
        Object key = keys.getFirst();
        if (SummaryLeafFilterCompiler.FIELDS.contains(key))
            return SummaryLeafFilterCompiler.compile((String) key, input.get(key));
        if (!(input.get(key) instanceof List<?> children) || children.isEmpty() || children.size() > 100)
            throw bad("AND/OR groups must contain between 1 and 100 filters.");
        var queries = new ArrayList<Map<String,Object>>();
        LinkedHashSet<String> required = null;
        for (Object child : children) {
            if (!(child instanceof Map<?,?> map)) throw bad("Each group member must be a filter.");
            var compiled = node(map, depth + 1);
            queries.add(compiled.query());
            if (required == null) required = new LinkedHashSet<>(compiled.requiredIds());
            else if (key.equals("and")) required.addAll(compiled.requiredIds());
            else required.retainAll(compiled.requiredIds());
        }
        Map<String,Object> bool = key.equals("and") ? Map.of("filter", queries)
                : Map.of("should", queries, "minimum_should_match", 1);
        return new Compiled(Map.of("bool", bool), List.copyOf(required));
    }
    private static ApiException bad(String message) { return new ApiException(BAD_USER_INPUT, message); }
}
