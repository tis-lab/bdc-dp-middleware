package org.biodatacatalyst.middleware.monarch;

import java.util.List;

import org.biodatacatalyst.middleware.terms.Suggestion;

public record SuggestionPage(List<Suggestion> items, int total) {
}
