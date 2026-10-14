package org.biodatacatalyst.middleware.terms;

import java.util.List;

import org.biodatacatalyst.middleware.shared.SearchItem;


public record AutocompleteResults(List<SearchItem> items, int total, int bdcVariableCount) {
}
