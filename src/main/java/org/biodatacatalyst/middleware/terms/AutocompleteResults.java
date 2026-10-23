package org.biodatacatalyst.middleware.terms;

import java.util.List;

public record AutocompleteResults(List<Suggestion> items, int total) {
}
