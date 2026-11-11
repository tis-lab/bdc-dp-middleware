package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.terms;

import java.util.List;

public record AutocompleteResults(List<Suggestion> items, int total) {
}
