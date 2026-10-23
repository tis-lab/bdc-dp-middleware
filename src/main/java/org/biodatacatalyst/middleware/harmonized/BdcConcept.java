package org.biodatacatalyst.middleware.harmonized;

import java.util.List;

import org.biodatacatalyst.middleware.shared.SearchItem;

public record BdcConcept(String conceptType,
                         String conceptLabel,
                         String bdchmType,
                         List<String> studies,
                         List<BdcCarrier> variables) implements SearchItem {

    public int studyCount() {
        return studies.size();
    }

    public String displayLabel() {
        return conceptLabel != null ? conceptLabel : conceptType;
    }

    public record BdcCarrier(String variableName, String variableLabel, List<String> studies) {
    }
}
