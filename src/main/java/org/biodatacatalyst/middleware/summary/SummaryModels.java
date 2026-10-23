package org.biodatacatalyst.middleware.summary;

import java.math.BigDecimal;
import java.util.List;

public final class SummaryModels {

    private SummaryModels() {
    }

    public record SummaryResponse(
            CohortSnapshot snapshot,
            List<String> notices,
            String release,
            String seed,
            List<String> filter,
            Integer minCount,
            List<SummaryTerm> terms,
            List<TermRelation> relations,
            List<ResearchStudy> researchStudies,
            List<StudyCounts> studies,
            ParticipantRecords records) {

        public SummaryResponse with(CohortSnapshot snapshot, List<String> notices) {
            return new SummaryResponse(snapshot, List.copyOf(notices), release, seed, filter,
                    minCount, terms, relations, researchStudies, studies, records);
        }

        public List<String> notices() {
            return notices == null ? List.of() : notices;
        }
    }

    public record ParticipantRecords(
            List<ConditionRecord> recordConditions,
            List<DemographyRecord> recordDemographics,
            List<MeasurementRecord> recordMeasurements) {

        public List<ConditionRecord> recordConditions() {
            return recordConditions == null ? List.of() : recordConditions;
        }

        public List<DemographyRecord> recordDemographics() {
            return recordDemographics == null ? List.of() : recordDemographics;
        }

        public List<MeasurementRecord> recordMeasurements() {
            return recordMeasurements == null ? List.of() : recordMeasurements;
        }
    }

    public record ConditionRecord(
            String id,
            String associatedParticipant,
            String conditionConcept,
            String conditionProvenance,
            String conditionStatus,
            String relationshipToParticipant) {
    }

    public record DemographyRecord(
            String id,
            String associatedParticipant,
            String sex,
            List<String> race,
            String ethnicity) {

        public List<String> race() {
            return race == null ? List.of() : race;
        }
    }

    public record MeasurementRecord(
            String id,
            String associatedParticipant,
            String observationType,
            Integer ageAtObservation,
            String valueQuantity) {
    }

    public record CohortSnapshot(int estimatedParticipants, int studyCount, int minCount) {
    }

    public record SummaryTerm(
            String curie,
            String label,
            String vocabulary,
            List<String> parents) {

        public List<String> parents() {
            return parents == null ? List.of() : parents;
        }
    }

    public record TermRelation(
            String subject,
            String predicate,
            String object,
            String relationSource) {
    }

    public record ResearchStudy(
            String id,
            String name,
            String researchProjectType,
            String program,
            List<String> principalInvestigator) {

        public List<String> principalInvestigator() {
            return principalInvestigator == null ? List.of() : principalInvestigator;
        }
    }

    public record StudyCounts(
            String researchStudy,
            Integer participantCount,
            List<ParticipantSummary> participants,
            List<DemographySummary> demographics,
            List<ConditionSummary> conditions,
            List<ProcedureSummary> procedures,
            List<DrugExposureSummary> drugExposures,
            List<MeasurementObservationSummary> measurements) {

        public List<ParticipantSummary> participants() {
            return participants == null ? List.of() : participants;
        }

        public List<DemographySummary> demographics() {
            return demographics == null ? List.of() : demographics;
        }

        public List<ConditionSummary> conditions() {
            return conditions == null ? List.of() : conditions;
        }

        public List<ProcedureSummary> procedures() {
            return procedures == null ? List.of() : procedures;
        }

        public List<DrugExposureSummary> drugExposures() {
            return drugExposures == null ? List.of() : drugExposures;
        }

        public List<MeasurementObservationSummary> measurements() {
            return measurements == null ? List.of() : measurements;
        }
    }

    public record ParticipantSummary(
            String id,
            String summarizedSlot,
            Integer participantCount,
            Integer recordCount,
            BigDecimal binLow,
            BigDecimal binHigh,
            String binUnit,
            List<ParticipantSummary> values) {

        public List<ParticipantSummary> values() {
            return values == null ? List.of() : values;
        }
    }

    public record DemographySummary(
            String id,
            String summarizedSlot,
            Integer participantCount,
            Integer recordCount,
            String sex,
            List<String> race,
            String ethnicity,
            List<DemographySummary> values) {

        public List<String> race() {
            return race == null ? List.of() : race;
        }

        public List<DemographySummary> values() {
            return values == null ? List.of() : values;
        }
    }

    public record ConditionSummary(
            String id,
            String conditionConcept,
            String conditionStatus,
            Integer participantCount,
            Integer recordCount,
            List<ConditionSummary> values) {

        public List<ConditionSummary> values() {
            return values == null ? List.of() : values;
        }
    }

    public record ProcedureSummary(
            String id,
            String procedureConcept,
            String procedureStatus,
            Integer participantCount,
            Integer recordCount,
            List<ProcedureSummary> values) {

        public List<ProcedureSummary> values() {
            return values == null ? List.of() : values;
        }
    }

    public record DrugExposureSummary(
            String id,
            String drugConcept,
            Integer participantCount,
            Integer recordCount,
            List<DrugExposureSummary> values) {

        public List<DrugExposureSummary> values() {
            return values == null ? List.of() : values;
        }
    }

    public record MeasurementObservationSummary(
            String id,
            String observationType,
            Integer participantCount,
            Integer recordCount,
            BigDecimal binLow,
            BigDecimal binHigh,
            String binUnit,
            List<MeasurementObservationSummary> values) {

        public List<MeasurementObservationSummary> values() {
            return values == null ? List.of() : values;
        }
    }
}
