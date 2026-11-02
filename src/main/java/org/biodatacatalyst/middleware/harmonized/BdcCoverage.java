package org.biodatacatalyst.middleware.harmonized;

import java.util.List;

public record BdcCoverage(int studyCount, List<String> studies, List<String> variableNames) {

    private static final BdcCoverage NONE = new BdcCoverage(0, List.of(), List.of());

    /** For a concept BDC has not harmonized, and for every concept when the index is disabled. */
    public static BdcCoverage none() {
        return NONE;
    }

    static BdcCoverage of(BdcConcept concept) {
        return new BdcCoverage(concept.studyCount(), concept.studies(),
                concept.variables().stream().map(BdcConcept.BdcCarrier::variableName).toList());
    }
}
