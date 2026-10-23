package org.biodatacatalyst.middleware.monarch;

import java.util.List;

import org.biodatacatalyst.middleware.terms.Term;


public record ConceptPage(List<Term> items, int total, int rowsReturned) {
}
