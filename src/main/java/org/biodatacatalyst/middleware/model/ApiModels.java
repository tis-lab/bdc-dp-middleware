package org.biodatacatalyst.middleware.model;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response containers. Records taken from the YAML files keep their original field names,
 * nested structures and null values.
 */
public final class ApiModels {
    private ApiModels() {
    }

    @Schema(description = "One study in the study selector.")
    public record StudySummary(
            @Schema(example = "study_one") String studyId,
            @Schema(example = "Example Study One") String name,
            @Schema(example = "500") int participantCount) {
    }

    @Schema(description = "A study with the record counts of its eight YAML files.")
    public record StudyDetail(
            @Schema(example = "study_one") String studyId,
            @Schema(example = "Example Study One") String name,
            @Schema(example = "500") int participantCount,
            @Schema(description = "Top-level record count per entity.") Map<String, Integer> recordCounts,
            @Schema(description = "Observations nested inside measurement sets. Counted separately because they "
                    + "are not top-level records; do not add them to recordCounts.measurement-sets.",
                    example = "2916") int embeddedObservationCount) {
    }

    @Schema(description = "A list of original YAML records.")
    public record RecordList(
            @Schema(example = "study_one") String studyId,
            @Schema(nullable = true, description = "Set when the list is scoped to one participant.")
            String participantId,
            @Schema(example = "conditions") String entity,
            int count,
            List<Map<String, Object>> records) {
    }

    @Schema(description = "One observation with its provenance inside the study.")
    public record Observation(
            @Schema(description = "standalone = a MeasurementObservation record; measurement-set = an observation "
                    + "nested in a MeasurementObservationSet.",
                    allowableValues = {"standalone", "measurement-set"}) String source,
            @Schema(nullable = true, description = "Identifier of the parent set, null for standalone observations.")
            String measurementSetId,
            @Schema(nullable = true, description = "Effective visit: the observation's own associated_visit, or the "
                    + "parent set's associated_visit when the observation does not carry one.")
            String visitId,
            @Schema(description = "The original observation record, unchanged.")
            Map<String, Object> observation) {
    }

    @Schema(description = "Standalone and nested observations combined. The two sources are disjoint, so the total "
            + "never double counts an observation.")
    public record MeasurementList(
            String studyId,
            String participantId,
            int count,
            @Schema(example = "11664") int standaloneCount,
            @Schema(example = "2916") int measurementSetCount,
            List<Observation> observations) {
    }

    @Schema(description = "One participant joined with every related record type in the same study.")
    public record ParticipantDetail(
            String studyId,
            Map<String, Object> participant,
            @Schema(nullable = true, description = "Linked Person record, null when the participant has no "
                    + "associated_person reference.") Map<String, Object> person,
            List<Map<String, Object>> demography,
            List<Map<String, Object>> conditions,
            List<Map<String, Object>> visits,
            List<Map<String, Object>> drugExposures,
            @Schema(description = "Standalone and nested observations with provenance.")
            List<Observation> measurements,
            @Schema(description = "The original measurement sets. Their nested observations already appear in "
                    + "measurements; do not count both lists.")
            List<Map<String, Object>> measurementSets,
            @Schema(description = "Explicit counts for each list above, so nested observations are never added twice.")
            Map<String, Integer> counts) {
    }
}
