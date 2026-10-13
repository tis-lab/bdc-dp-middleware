package org.biodatacatalyst.middleware.terms;

import java.util.List;

import org.biodatacatalyst.middleware.harmonized.BdcCoverage;
import org.biodatacatalyst.middleware.harmonized.BdcVariable;


public record TermResults(List<BdcVariable> bdcVariables,
                          List<Term> items,
                          int total,
                          int limit,
                          int offset,
                          boolean hasMore) {


    public record Term(String id,
                       String label,
                       String category,
                       String description,
                       List<String> synonyms,
                       String displayLabel,
                       BdcCoverage bdc) {

        /** The same concept with its BDC annotation attached. */
        public Term withBdc(BdcCoverage coverage) {
            return new Term(id, label, category, description, synonyms, displayLabel, coverage);
        }
    }

    /** Straight from Monarch, before anything BDC knows has been attached. */
    public static TermResults fromMonarch(List<Term> items, int total, int limit, int offset,
                                          boolean hasMore) {
        return new TermResults(List.of(), items, total, limit, offset, hasMore);
    }

    /** The same page with BDC matches and per-concept annotations attached. */
    public TermResults withBdc(List<BdcVariable> variables, List<Term> annotated) {
        return new TermResults(variables, annotated, total, limit, offset, hasMore);
    }
}
