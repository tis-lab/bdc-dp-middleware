package org.biodatacatalyst.middleware.terms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.biodatacatalyst.middleware.monarch.MonarchClient;
import org.biodatacatalyst.middleware.shared.ApiException;

import static org.biodatacatalyst.middleware.shared.ApiException.Code.BAD_USER_INPUT;


@Service
public class TermService {

    private static final Logger log = LoggerFactory.getLogger(TermService.class);

    static final int MAX_QUERY_CODE_POINTS = 200;

    static final int MAX_LIMIT = 100;

    static final int MAX_OFFSET = 10_000;

    private final MonarchClient monarch;

    public TermService(MonarchClient monarch) {
        this.monarch = monarch;
    }

    public TermResults terms(String query, int limit, int offset) {
        if (query == null || query.isBlank()) {
            throw new ApiException(BAD_USER_INPUT, "query must not be blank.");
        }

        String normalized = query.strip();

        if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY_CODE_POINTS) {
            throw new ApiException(BAD_USER_INPUT,
                    "query must contain at most " + MAX_QUERY_CODE_POINTS + " characters.");
        }

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(BAD_USER_INPUT, "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new ApiException(BAD_USER_INPUT, "offset must be between 0 and " + MAX_OFFSET + ".");
        }

        log.debug("Resolving term of {} characters (limit={}, offset={})",
                normalized.length(), limit, offset);

        return monarch.search(normalized, limit, offset);
    }
}
