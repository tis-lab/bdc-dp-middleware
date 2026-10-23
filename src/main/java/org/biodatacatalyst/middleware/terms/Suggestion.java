package org.biodatacatalyst.middleware.terms;

import java.util.List;

public record Suggestion(String id,
                         String label,
                         String category,
                         String description,
                         List<String> synonyms,
                         String displayLabel) {
}
