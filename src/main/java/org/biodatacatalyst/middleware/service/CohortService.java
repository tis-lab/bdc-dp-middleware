package org.biodatacatalyst.middleware.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class CohortService {
    // Proposed sprint definitions; no age, threshold or temporal criteria.
    private static final List<Cohort> COHORTS = List.of(
            new Cohort("hypertension", "Hypertension", List.of("HP:0000822", "MONDO:0005044", "MONDO:0001134", "MONDO:0001200",
                    "MONDO:0006947", "MONDO:0001105", "MONDO:0001302", "MONDO:0100078", "MONDO:1030007",
                    "MONDO:0006846", "MONDO:0006796", "MONDO:0005081", "MONDO:0015924", "MONDO:0005080",
                    "HP:0100817", "HP:0100735", "HP:0100602", "HP:0002092", "HP:0001409"
            )),
            new Cohort("diabetes", "Diabetes", List.of("MONDO:0005015", "HP:0000819", "MONDO:0005148", "MONDO:0005827",
                    "MONDO:0005406", "HP:0005978", "HP:0000819", "HP:0005978")));

    private final StudyDataService data;

    public CohortService(StudyDataService data) {
        this.data = data;
    }

    public List<Cohort> cohorts() {
        return COHORTS;
    }

    public Cohort cohort(String id) {
        return COHORTS.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Unknown cohort: " + id));
    }

    public CohortResponse participants(String cohortId, String studyId) {
        Cohort cohort = cohort(cohortId);
        List<String> studies = data.studies();
        if (studyId != null) {
            data.entities(studyId); // Validate the study using the same allowlist.
            studies = List.of(studyId);
        }
        List<ParticipantMatch> matches = new ArrayList<>();
        for (String study : studies) {
            // Match within each study; never join records across study folders.
            Set<String> ids = data.read(study, "conditions").stream()
                    .filter(c -> "PRESENT".equals(c.path("condition_status").asText()))
                    .filter(c -> "ONESELF".equals(c.path("relationship_to_participant").asText()))
                    .filter(c -> cohort.conditionCodes().contains(c.path("condition_concept").asText()))
                    .map(c -> c.path("associated_participant").asText())
                    .collect(Collectors.toSet());
            for (JsonNode participant : data.read(study, "participants")) {
                if (ids.contains(participant.path("id").asText())) {
                    matches.add(new ParticipantMatch(study, participant));
                }
            }
        }
        return new CohortResponse(cohort.id(), matches.size(), matches);
    }

    public record Cohort(String id, String name, List<String> conditionCodes) {}
    public record ParticipantMatch(String studyId, JsonNode participant) {}
    public record CohortResponse(String cohortId, int matchedParticipantCount,
                                 List<ParticipantMatch> participants) {}
}
