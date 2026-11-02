package org.biodatacatalyst.middleware.summary;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

public final class SummaryModels {
    private SummaryModels() {}

    public record SummaryResponse(
            String release,
            String seed,
            List<String> filter,
            BigInteger min_count,
            List<SummaryTerm> terms,
            List<TermRelation> relations,
            List<ResearchStudy> research_studies,
            List<StudySummary> studies) {
        public SummaryResponse {
            filter = filter == null ? List.of() : List.copyOf(filter);
            terms = terms == null ? List.of() : List.copyOf(terms);
            relations = relations == null ? List.of() : List.copyOf(relations);
            research_studies = research_studies == null ? List.of() : List.copyOf(research_studies);
            studies = studies == null ? List.of() : List.copyOf(studies);
        }
    }

    public record SummaryTerm(
            String curie,
            String label,
            String vocabulary,
            List<String> parents) {
        public SummaryTerm {
            parents = parents == null ? List.of() : List.copyOf(parents);
        }
    }

    public record TermRelation(
            String subject,
            String predicate,
            String object,
            String relation_source) {
    }

    public record ResearchStudy(
            String id,
            String name,
            String research_project_type,
            String program,
            List<String> principal_investigator) {
        public ResearchStudy {
            principal_investigator = principal_investigator == null ? List.of() : List.copyOf(principal_investigator);
        }
    }

    public record StudySummary(
            String research_study,
            BigInteger participant_count,
            List<ParticipantSummary> participants,
            List<DemographySummary> demographics,
            List<ConditionSummary> conditions,
            List<ProcedureSummary> procedures,
            List<DrugExposureSummary> drug_exposures,
            List<MeasurementObservationSummary> measurements) {
        public StudySummary {
            participants = participants == null ? List.of() : List.copyOf(participants);
            demographics = demographics == null ? List.of() : List.copyOf(demographics);
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            procedures = procedures == null ? List.of() : List.copyOf(procedures);
            drug_exposures = drug_exposures == null ? List.of() : List.copyOf(drug_exposures);
            measurements = measurements == null ? List.of() : List.copyOf(measurements);
        }
    }

    public record ParticipantSummary(
            String id,
            String summarized_slot,
            BigInteger participant_count,
            BigInteger record_count,
            BigDecimal bin_low,
            BigDecimal bin_high,
            String bin_unit,
            List<ParticipantSummary> values) {
        public ParticipantSummary {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record DemographySummary(
            String id,
            String summarized_slot,
            BigInteger participant_count,
            BigInteger record_count,
            String sex,
            List<String> race,
            String ethnicity,
            List<DemographySummary> values) {
        public DemographySummary {
            race = race == null ? List.of() : List.copyOf(race);
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record ConditionSummary(
            String id,
            String condition_concept,
            String condition_status,
            BigInteger participant_count,
            BigInteger record_count,
            List<ConditionSummary> values) {
        public ConditionSummary {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record ProcedureSummary(
            String id,
            String procedure_concept,
            String procedure_status,
            BigInteger participant_count,
            BigInteger record_count,
            List<ProcedureSummary> values) {
        public ProcedureSummary {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record DrugExposureSummary(
            String id,
            String drug_concept,
            BigInteger participant_count,
            BigInteger record_count,
            List<DrugExposureSummary> values) {
        public DrugExposureSummary {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record MeasurementObservationSummary(
            String id,
            String observation_type,
            BigInteger participant_count,
            BigInteger record_count,
            BigDecimal bin_low,
            BigDecimal bin_high,
            String bin_unit,
            BigDecimal range_low,
            BigDecimal range_high,
            List<MeasurementObservationSummary> values) {
        public MeasurementObservationSummary {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
