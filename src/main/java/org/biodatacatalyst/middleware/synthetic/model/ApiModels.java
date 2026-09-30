package org.biodatacatalyst.middleware.synthetic.model;

import java.util.List;
import java.util.Map;

/**
 * Response containers. Records taken from the YAML files keep their original field names,
 * nested structures and null values.
 */
public final class ApiModels {
    private ApiModels() {
    }

    public record StudySummary(
            String studyId,
            String name,
            int participantCount) {
    }

    public record StudyDetail(
            String studyId,
            String name,
            int participantCount,
            Map<String, Integer> recordCounts,
            int embeddedObservationCount) {
    }

    public record RecordList(
            String studyId,

            String participantId,
            String entity,
            int count,
            List<Map<String, Object>> records) {
    }

    public record Observation(
            String source,

            String measurementSetId,

            String visitId,

            Map<String, Object> observation) {
    }

    public record MeasurementList(
            String studyId,
            String participantId,
            int count,
            int standaloneCount,
            int measurementSetCount,
            List<Observation> observations) {
    }

    public record ParticipantDetail(
            String studyId,
            Map<String, Object> participant,
            Map<String, Object> person,
            List<Map<String, Object>> demography,
            List<Map<String, Object>> conditions,
            List<Map<String, Object>> visits,
            List<Map<String, Object>> drugExposures,

            List<Observation> measurements,

            List<Map<String, Object>> measurementSets,

            ParticipantCounts counts) {
    }


    public record ParticipantCounts(
            int demography,
            int conditions,
            int visits,
            int drugExposures,
            int measurementSets,
            int standaloneMeasurements,
            int measurementSetObservations,
            int measurements) {
    }
}
