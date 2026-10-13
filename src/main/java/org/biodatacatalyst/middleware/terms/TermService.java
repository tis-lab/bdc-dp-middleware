package org.biodatacatalyst.middleware.terms;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.biodatacatalyst.middleware.harmonized.BdcVariable;
import org.biodatacatalyst.middleware.harmonized.HarmonizedVariableLibrary;
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
    private final HarmonizedVariableLibrary bdcVariables;

    public TermService(MonarchClient monarch, HarmonizedVariableLibrary bdcVariables) {
        this.monarch = monarch;
        this.bdcVariables = bdcVariables;
    }

    public TermResults terms(String query, int limit, int offset) {
        String normalized = validatedQuery(query);

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(BAD_USER_INPUT, "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new ApiException(BAD_USER_INPUT, "offset must be between 0 and " + MAX_OFFSET + ".");
        }

        log.debug("Resolving term of {} characters (limit={}, offset={})",
                normalized.length(), limit, offset);

        TermResults concepts = monarch.search(normalized, limit, offset);
        return attachBdc(normalized, concepts);
    }


    private TermResults attachBdc(String normalized, TermResults concepts) {
        List<BdcVariable> matching = bdcVariables.search(normalized);

        List<TermResults.Term> annotated = concepts.items().stream()
                .map(term -> term.withBdc(bdcVariables.coverageFor(term.id())))
                .toList();

        if (log.isDebugEnabled()) {
            log.debug("Matched {} BDC variable(s); annotated {} concept(s), {} carrying RTI mappings",
                    matching.size(), annotated.size(),
                    annotated.stream().filter(t -> t.bdc().studyCount() > 0).count());
        }

        return concepts.withBdc(matching, annotated);
    }


    public AutocompleteResults autocomplete(String query) {
        String normalized = validatedQuery(query);

        log.debug("Autocompleting {} characters", normalized.length());

        AutocompleteResults suggestions = monarch.autocomplete(normalized);

        // Found by searching BDC's own index on the typed text, not by filtering the suggestions.
        List<BdcVariable> matching = bdcVariables.search(normalized);

        List<AutocompleteResults.Suggestion> annotated = suggestions.items().stream()
                .map(suggestion -> suggestion.withBdc(bdcVariables.coverageFor(suggestion.id())))
                .toList();

        if (log.isDebugEnabled()) {
            log.debug("Matched {} BDC variable(s); annotated {} suggestion(s), {} carrying RTI mappings",
                    matching.size(), annotated.size(),
                    annotated.stream().filter(s -> s.bdc().studyCount() > 0).count());
        }

        return suggestions.withBdc(matching, annotated);
    }


    private static String validatedQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new ApiException(BAD_USER_INPUT, "query must not be blank.");
        }
        String normalized = query.strip();
        if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY_CODE_POINTS) {
            throw new ApiException(BAD_USER_INPUT,
                    "query must contain at most " + MAX_QUERY_CODE_POINTS + " characters.");
        }
        return normalized;
    }
}
