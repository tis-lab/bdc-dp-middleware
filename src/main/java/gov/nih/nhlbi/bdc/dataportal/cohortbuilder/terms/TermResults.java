package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.terms;

import java.util.List;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.SearchItem;

public record TermResults(List<SearchItem> items,
                          int total,
                          int limit,
                          int offset,
                          boolean hasMore,
                          int bdcConceptCount) {
}
