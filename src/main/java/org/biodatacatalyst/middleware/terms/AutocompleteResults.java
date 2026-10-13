package org.biodatacatalyst.middleware.terms;

import java.util.List;

import org.biodatacatalyst.middleware.harmonized.BdcCoverage;
import org.biodatacatalyst.middleware.harmonized.BdcVariable;

public record AutocompleteResults(List<BdcVariable> bdcVariables,
                                  List<Suggestion> items,
                                  int total) {

    public record Suggestion(String id,
                             String label,
                             String category,
                             String description,
                             String displayLabel,
                             BdcCoverage bdc) {

        public Suggestion withBdc(BdcCoverage coverage) {
            return new Suggestion(id, label, category, description, displayLabel, coverage);
        }
    }

    public static AutocompleteResults fromMonarch(List<Suggestion> items, int total) {
        return new AutocompleteResults(List.of(), items, total);
    }

    public AutocompleteResults withBdc(List<BdcVariable> variables, List<Suggestion> annotated) {
        return new AutocompleteResults(variables, annotated, total);
    }
}
