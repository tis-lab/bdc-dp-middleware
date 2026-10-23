package org.biodatacatalyst.middleware.terms;

import java.util.List;

import org.biodatacatalyst.middleware.harmonized.BdcCoverage;
import org.biodatacatalyst.middleware.shared.SearchItem;


public record Term(String id,
                   String label,
                   String category,
                   String description,
                   List<String> synonyms,
                   String displayLabel,
                   BdcCoverage bdc) implements SearchItem {

    /** The same concept with its BDC annotation attached. */
    public Term withBdc(BdcCoverage coverage) {
        return new Term(id, label, category, description, synonyms, displayLabel, coverage);
    }
}
