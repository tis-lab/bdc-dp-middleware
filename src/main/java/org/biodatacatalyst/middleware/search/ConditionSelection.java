package org.biodatacatalyst.middleware.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.biodatacatalyst.middleware.shared.ApiException;
import static org.biodatacatalyst.middleware.shared.ApiException.Code.BAD_USER_INPUT;

/** Exact condition keys supplied by the UI */
record ConditionSelection(List<String> keywords) {
    ConditionSelection {
        if (keywords == null || keywords.isEmpty() || keywords.size() > 50) {
            throw new ApiException(BAD_USER_INPUT, "Supply between 1 and 50 condition keywords.");
        }
        var unique = new LinkedHashSet<String>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.length() > 500
                    || !keyword.matches("(PRESENT|ABSENT)\\|ONESELF\\|[A-Za-z][A-Za-z0-9._-]*:[A-Za-z0-9._-]+")) {
                throw new ApiException(BAD_USER_INPUT,
                        "Each keyword must be PRESENT|ONESELF|<CURIE> or ABSENT|ONESELF|<CURIE> (maximum 500 characters).");
            }
            unique.add(keyword);
        }
        keywords = List.copyOf(unique);
    }

    Map<String, Object> query() {
        var groups = new ArrayList<Map<String, Object>>();
        for (String status : List.of("PRESENT", "ABSENT")) {
            var keys = keywords.stream().filter(key -> key.startsWith(status + "|")).toList();
            if (!keys.isEmpty()) {
                // Elasticsearch terms matches any key within this group.
                groups.add(Map.of("terms", Map.of("_conditions", keys)));
            }
        }
        // Both non-empty groups must match the same parent participant document.
        return Map.of("bool", Map.of("filter", groups));
    }
}
