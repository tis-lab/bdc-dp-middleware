package org.biodatacatalyst.middleware.monarch;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.biodatacatalyst.middleware.shared.ApiException;
import org.biodatacatalyst.middleware.terms.TermResults;
import org.biodatacatalyst.middleware.terms.TermResults.Term;

import static org.biodatacatalyst.middleware.shared.ApiException.Code.UPSTREAM_INVALID_RESPONSE;

/** Boundary validation prevents a changed upstream contract from masquerading as zero matches. */
@Component
public class MonarchResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(MonarchResponseMapper.class);

    public TermResults map(JsonNode root, int requestedLimit, int requestedOffset) {
        if (root == null || !root.isObject() || !root.path("items").isArray()) {
            throw malformed("body is not an object with an items array");
        }

        int total = nonnegativeInt(root, "total");
        int limit = nonnegativeInt(root, "limit");
        int offset = nonnegativeInt(root, "offset");
        JsonNode rows = root.get("items");

        boolean emptyPageInsideTheResultSet = rows.isEmpty() && offset < total;
        boolean pagingDoesNotMatchTheRequest = limit != requestedLimit || offset != requestedOffset;
        boolean pageOverflowsItsOwnBounds = rows.size() > limit
                || (!rows.isEmpty() && (long) offset + rows.size() > total);
        if (emptyPageInsideTheResultSet || pagingDoesNotMatchTheRequest || pageOverflowsItsOwnBounds) {
            throw malformed("paging does not add up: requested limit=%d offset=%d, received limit=%d offset=%d total=%d items=%d"
                    .formatted(requestedLimit, requestedOffset, limit, offset, total, rows.size()));
        }

        List<Term> items = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                throw malformed("an item is not an object");
            }
            String id = requiredText(row, "id");
            String label = requiredText(row, "name");
            items.add(new Term(
                    id,
                    label,
                    requiredText(row, "category"),
                    optionalText(row, "description"),
                    synonyms(row.get("synonym")),
                    label + " (" + id + ")"));
        }

        boolean hasMore = (long) offset + items.size() < total;
        return new TermResults(List.copyOf(items), total, limit, offset, hasMore);
    }

    private static int nonnegativeInt(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw malformed("field '" + field + "' is not a non-negative integer");
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode row, String field) {
        String value = optionalText(row, field);
        if (value == null || value.isBlank()) {
            throw malformed("required field '" + field + "' is missing or blank");
        }
        return value;
    }

    private static String optionalText(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw malformed("field '" + field + "' is not a string");
        }
        return value.textValue();
    }

    private static List<String> synonyms(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw malformed("field 'synonym' is not an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw malformed("field 'synonym' contains a non-string value");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    static ApiException malformed() {
        return malformed("the response body could not be decoded");
    }

    static ApiException malformed(String reason) {
        log.warn("Monarch response rejected: {}", reason);
        return new ApiException(UPSTREAM_INVALID_RESPONSE, "Monarch returned an unexpected response.");
    }
}
