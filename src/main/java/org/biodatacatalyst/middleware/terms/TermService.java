package org.biodatacatalyst.middleware.terms;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.biodatacatalyst.middleware.harmonized.BdcConcept;
import org.biodatacatalyst.middleware.harmonized.HarmonizedVariableLibrary;
import org.biodatacatalyst.middleware.monarch.ConceptPage;
import org.biodatacatalyst.middleware.monarch.MonarchClient;
import org.biodatacatalyst.middleware.monarch.SuggestionPage;
import org.biodatacatalyst.middleware.shared.ApiException;
import org.biodatacatalyst.middleware.shared.SearchItem;

import static org.biodatacatalyst.middleware.shared.ApiException.Code.BAD_USER_INPUT;


@Service
public class TermService {

    private static final Logger log = LoggerFactory.getLogger(TermService.class);

    static final int MAX_QUERY_CODE_POINTS = 200;

    static final int MAX_LIMIT = 100;

    static final int MAX_OFFSET = 10_000;

    private final MonarchClient monarch;
    private final HarmonizedVariableLibrary bdc;

    public TermService(MonarchClient monarch, HarmonizedVariableLibrary bdc) {
        this.monarch = monarch;
        this.bdc = bdc;
    }

    public TermResults terms(String query, int limit, int offset) {
        String normalized = validatedQuery(query);

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(BAD_USER_INPUT, "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new ApiException(BAD_USER_INPUT, "offset must be between 0 and " + MAX_OFFSET + ".");
        }

        List<BdcConcept> matched = bdc.search(normalized);
        int bdcConceptCount = matched.size();

        int from = Math.min(offset, bdcConceptCount);
        int to = Math.min(bdcConceptCount, offset + limit);
        List<BdcConcept> onThisPage = matched.subList(from, Math.max(from, to));

        int wanted = limit - onThisPage.size();
        int upstreamOffset = Math.max(0, offset - bdcConceptCount);

        log.debug("Resolving term of {} characters (limit={}, offset={}): {} BDC concept(s) then {} upstream from {}",
                normalized.length(), limit, offset, onThisPage.size(), wanted, upstreamOffset);

        // wanted can be 0 when BDC concepts fill the page. The provider still reports its total for
        // a zero-size page, which is what keeps total and hasMore correct.
        ConceptPage upstream = monarch.search(normalized, wanted, upstreamOffset);

        List<SearchItem> items = merge(onThisPage, annotate(upstream.items()));
        int total = bdcConceptCount + upstream.total();

        // Computed from what the provider actually advanced by, not from items.size(). A repeated
        // identifier dropped by the mapper still consumed an upstream slot, and using the shorter
        // list here would report a further page at the end of the result set.
        boolean hasMore = (long) offset + onThisPage.size() + upstream.rowsReturned() < total;

        return new TermResults(items, total, limit, offset, hasMore, bdcConceptCount);
    }

    public AutocompleteResults autocomplete(String query) {
        String normalized = validatedQuery(query);

        SuggestionPage suggestions = monarch.autocomplete(normalized);

        log.debug("Autocompleted {} characters: {} suggestion(s)",
                normalized.length(), suggestions.items().size());

        // Nothing is attached. The BDC index is not consulted on this path at all.
        return new AutocompleteResults(suggestions.items(), suggestions.total());
    }

    /** BDC concepts first, then upstream. The order is the display order; nothing re-ranks it. */
    private static List<SearchItem> merge(List<BdcConcept> bdcConcepts, List<Term> upstream) {
        List<SearchItem> items = new ArrayList<>(bdcConcepts.size() + upstream.size());
        items.addAll(bdcConcepts);
        items.addAll(upstream);
        return List.copyOf(items);
    }

    /** Attaches each concept's BDC coverage. A map lookup per concept; no upstream traffic. */
    private List<Term> annotate(List<Term> upstream) {
        return upstream.stream()
                .map(term -> term.withBdc(bdc.coverageFor(term.id())))
                .toList();
    }

    /** Shared by both operations: the term itself is validated identically either way. */
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
