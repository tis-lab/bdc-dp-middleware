package org.biodatacatalyst.middleware.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import org.biodatacatalyst.middleware.service.StudyRepository.Study;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class CohortService {

    /** Only conditions the participant has themselves, currently recorded, are counted. */
    private static final String REQUIRED_STATUS = "PRESENT";
    private static final String REQUIRED_RELATIONSHIP = "ONESELF";

    private static final List<CohortDefinition> COHORTS = List.of(
            cohort("hypertension", "Hypertension",
                    "MONDO:0005044", "MONDO:0001134", "MONDO:0001200", "MONDO:0006947", "MONDO:0001105",
                    "MONDO:0001302", "MONDO:0100078", "MONDO:1030007", "MONDO:0006846", "MONDO:0006796",
                    "MONDO:0005081", "MONDO:0015924", "MONDO:0005080",
                    "HP:0000822", "HP:0100817", "HP:0100735", "HP:0100602", "HP:0002092", "HP:0001409"),
            cohort("diabetes", "Diabetes",
                    "MONDO:0005015", "MONDO:0005148", "MONDO:0005827", "MONDO:0005406",
                    "HP:0000819", "HP:0005978"));

    private final StudyRepository repository;

    public CohortService(StudyRepository repository) {
        this.repository = repository;
    }

    public List<CohortDefinition> cohorts() {
        return COHORTS;
    }

    public CohortDefinition cohort(String cohortId) {
        return COHORTS.stream()
                .filter(cohort -> cohort.cohortId().equals(cohortId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Unknown cohort: " + cohortId
                        + ". Known cohorts: " + COHORTS.stream().map(CohortDefinition::cohortId).toList()));
    }

    /** Matching participants, optionally restricted to one study. Never joins across studies. */
    public CohortParticipants participants(String cohortId, String studyId) {
        CohortDefinition cohort = cohort(cohortId);
        List<ParticipantMatch> matches = new ArrayList<>();
        for (String id : studiesToSearch(studyId)) {
            Study study = repository.study(id);
            for (String participantId : matchingParticipantIds(study, cohort)) {
                matches.add(new ParticipantMatch(study.id(), study.participant(participantId)));
            }
        }
        return new CohortParticipants(cohort.cohortId(), blank(studyId) ? null : studyId.trim(),
                matches.size(), matches);
    }

    /** Distinct matching participants per study, including studies with no matches. */
    public CohortCounts counts(String cohortId) {
        CohortDefinition cohort = cohort(cohortId);
        Map<String, Integer> byStudy = new LinkedHashMap<>();
        int total = 0;
        for (String id : repository.studyIds()) {
            int matched = matchingParticipantIds(repository.study(id), cohort).size();
            byStudy.put(id, matched);
            total += matched;
        }
        return new CohortCounts(cohort.cohortId(), total,
                byStudy.entrySet().stream().map(e -> new StudyCount(e.getKey(), e.getValue())).toList());
    }

    /**
     * Identifiers of participants in one study with at least one matching condition. A set, so a
     * participant with several matching conditions is counted once.
     */
    private Set<String> matchingParticipantIds(Study study, CohortDefinition cohort) {
        Set<String> codes = Set.copyOf(cohort.conditionCodes());
        Set<String> matched = new LinkedHashSet<>();
        for (JsonNode condition : study.records("conditions")) {
            if (!codes.contains(condition.path("condition_concept").asText(""))
                    || !REQUIRED_STATUS.equals(condition.path("condition_status").asText(""))
                    || !REQUIRED_RELATIONSHIP.equals(condition.path("relationship_to_participant").asText(""))) {
                continue;
            }
            String participantId = condition.path("associated_participant").asText("");
            // Ignore references to participants that are not in this study's Participant file.
            if (study.participantsById().containsKey(participantId)) {
                matched.add(participantId);
            }
        }
        return matched;
    }

    private List<String> studiesToSearch(String studyId) {
        if (blank(studyId)) {
            return repository.studyIds();
        }
        return List.of(repository.study(studyId.trim()).id());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static CohortDefinition cohort(String cohortId, String name, String... codes) {
        return new CohortDefinition(cohortId, name, REQUIRED_STATUS, REQUIRED_RELATIONSHIP,
                List.copyOf(new LinkedHashSet<>(List.of(codes))));
    }

    @Schema(description = "A cohort rule. A participant matches when any of their conditions has one of "
            + "conditionCodes with the required status and relationship.")
    public record CohortDefinition(
            @Schema(example = "hypertension") String cohortId,
            @Schema(example = "Hypertension") String name,
            @Schema(example = "PRESENT") String requiredConditionStatus,
            @Schema(example = "ONESELF") String requiredRelationshipToParticipant,
            List<String> conditionCodes) {
    }

    public record StudyCount(String studyId, int matchedParticipantCount) {
    }

    @Schema(description = "Distinct matching participants per study. A participant with several matching "
            + "conditions is counted once.")
    public record CohortCounts(String cohortId, int matchedParticipantCount, List<StudyCount> byStudy) {
    }

    public record ParticipantMatch(
            String studyId,
            @Schema(implementation = Map.class, type = "object",
                    additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
                    description = "The original Participant record, including nested fields and null values.")
            JsonNode participant) {
    }

    public record CohortParticipants(
            String cohortId,
            @Schema(nullable = true, description = "Set when the search was restricted to one study.")
            String studyId,
            int matchedParticipantCount,
            List<ParticipantMatch> participants) {
    }
}
