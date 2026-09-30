package org.biodatacatalyst.middleware.terms;

import java.util.List;

public record TermResults(List<Term> items, int total, int limit, int offset, boolean hasMore) {

    public record Term(String id,
                       String label,
                       String category,
                       String description,
                       List<String> synonyms,
                       String displayLabel) {
    }
}
