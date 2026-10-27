package org.biodatacatalyst.middleware.harmonized;

import java.util.List;

import org.biodatacatalyst.middleware.shared.SearchItem;

public record BdcConcept(String conceptType,
                         String conceptLabel,
                         BdchmTypeEnum bdchmType,
                         List<String> bdchmClasses,
                         List<SourceMapping> sourceMappings,
                         List<String> studies,
                         List<BdcCarrier> variables) implements SearchItem {

    /** Compatibility for callers constructing a concept without exact source metadata. */
    public BdcConcept(String conceptType, String conceptLabel, BdchmTypeEnum bdchmType,
                      List<String> studies, List<BdcCarrier> variables) {
        this(conceptType, conceptLabel, bdchmType, List.of(), List.of(), studies, variables);
    }

    public record SourceMapping(String study, String bdchmClass,
                                String conceptSlot, String sourceFile) {}

    public int studyCount() {
        return studies.size();
    }

    public String displayLabel() {
        return conceptLabel != null && !conceptLabel.isBlank() ? conceptLabel : conceptType;
    }

    public record BdcCarrier(String variableName, String variableLabel, List<String> studies) {
    }
}
