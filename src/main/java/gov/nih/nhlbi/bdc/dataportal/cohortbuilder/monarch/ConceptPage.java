package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.monarch;

import java.util.List;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.terms.Term;


public record ConceptPage(List<Term> items, int total, int rowsReturned) {
}
