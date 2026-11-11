package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.monarch;

import java.util.List;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.terms.Suggestion;

public record SuggestionPage(List<Suggestion> items, int total) {
}
