package org.biodatacatalyst.middleware.harmonized;

import java.util.List;

public record BdcCoverage(int studyCount, List<String> studies, List<String> variableNames) {

    private static final BdcCoverage NONE = new BdcCoverage(0, List.of(), List.of());

    /** For a concept BDC has not harmonized, and for every concept when the index is disabled. */
    public static BdcCoverage none() {
        return NONE;
    }
}
