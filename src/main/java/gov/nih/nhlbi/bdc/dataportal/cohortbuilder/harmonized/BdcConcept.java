package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.harmonized;

import java.util.List;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.SearchItem;

public record BdcConcept(String conceptType,
                         String conceptLabel,
                         BdchmTypeEnum bdchmType,
                         List<String> bdchmClasses,
                         List<SourceMapping> sourceMappings,
                         List<String> studies,
                         List<BdcCarrier> variables,
                         List<IntegratedVariable> integratedVariables) implements SearchItem {

    /** Compatibility for callers constructing a concept without exact source metadata. */
    public BdcConcept(String conceptType, String conceptLabel, BdchmTypeEnum bdchmType,
                      List<String> studies, List<BdcCarrier> variables) {
        this(conceptType, conceptLabel, bdchmType, List.of(), List.of(), studies, variables, List.of());
    }

    public BdcConcept(String conceptType, String conceptLabel, BdchmTypeEnum bdchmType,
                      List<String> classes, List<SourceMapping> mappings,
                      List<String> studies, List<BdcCarrier> variables) {
        this(conceptType, conceptLabel, bdchmType, classes, mappings, studies, variables, List.of());
    }

    public record IntegratedVariable(String id, String label, String description,
                                     BdchmTypeEnum bdchmType, List<StudyMapping> studyMappings) {}
    public record StudyMapping(String compoundVariableId, String label, String description,
                               String studyId, String studyName, List<SourceVariable> sourceVariables) {}
    public record SourceVariable(String id, String variableName, String description, String dataType,
                                 String unit, String datasetId, boolean nameIsAccession,
                                 boolean valueVariable, List<String> microschemaSlots) {}

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
