package org.biodatacatalyst.middleware.terms;

import java.util.List;

import org.biodatacatalyst.middleware.shared.SearchItem;

public record TermResults(List<SearchItem> items,
                          int total,
                          int limit,
                          int offset,
                          boolean hasMore,
                          int bdcVariableCount) {
}
