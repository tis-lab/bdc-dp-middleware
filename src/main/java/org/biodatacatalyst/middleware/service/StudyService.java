package org.biodatacatalyst.middleware.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import org.biodatacatalyst.middleware.model.ApiModels.MeasurementList;
import org.biodatacatalyst.middleware.model.ApiModels.Observation;
import org.biodatacatalyst.middleware.model.ApiModels.ParticipantDetail;
import org.biodatacatalyst.middleware.model.ApiModels.RecordList;
import org.biodatacatalyst.middleware.model.ApiModels.StudyDetail;
import org.biodatacatalyst.middleware.model.ApiModels.StudySummary;
import org.biodatacatalyst.middleware.service.StudyRepository.Study;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/** Study, participant and measurement views. Every join stays inside one study. */
@Service
public class StudyService {

    private static final TypeReference<Map<String, Object>> RECORD = new TypeReference<>() {
    };

    /** Field holding the coded concept, per entity that supports the concept filter. */
    private static final Map<String, String> CONCEPT_FIELDS = Map.of(
            "conditions", "condition_concept",
            "drug-exposures", "drug_concept",
            "measurements", "observation_type");

    /** Field holding the status, per entity that supports the status filter. */
    private static final Map<String, String> STATUS_FIELDS = Map.of(
            "conditions", "condition_status",
            "drug-exposures", "exposure_status");

    private static final String RELATIONSHIP_FIELD = "relationship_to_participant";

    private final StudyRepository repository;
    private final ObjectMapper mapper;

    public StudyService(StudyRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public List<StudySummary> studies() {
        return repository.studyIds().stream()
                .map(repository::study)
                .map(study -> new StudySummary(study.id(), study.name(), study.participantCount()))
                .toList();
    }

    public StudyDetail study(String studyId) {
        Study study = repository.study(studyId);
        return new StudyDetail(study.id(), study.name(), study.participantCount(),
                study.recordCounts(), study.embeddedObservationCount());
    }

    /** All records of one entity, exactly as they appear in the YAML file. */
    public RecordList records(String studyId, String entity) {
        Study study = repository.study(studyId);
        return new RecordList(study.id(), null, entity, study.records(entity).size(),
                maps(study.records(entity)));
    }

    public Map<String, Object> participant(String studyId, String participantId) {
        return map(repository.study(studyId).participant(participantId));
    }

    /**
     * Records of one entity belonging to a participant. {@code persons} resolves the
     * {@code associated_person} reference and returns zero or one record.
     */
    public RecordList relatedRecords(String studyId, String participantId, String entity,
                                     String concept, String status, String relationship) {
        Study study = repository.study(studyId);
        study.participant(participantId);

        List<JsonNode> rows = "persons".equals(entity)
                ? linkedPerson(study, participantId).map(List::of).orElseGet(List::of)
                : study.recordsFor(participantId, entity);

        rows = filter(rows, entity, CONCEPT_FIELDS.get(entity), concept, "concept");
        rows = filter(rows, entity, STATUS_FIELDS.get(entity), status, "status");
        rows = filter(rows, entity, "conditions".equals(entity) ? RELATIONSHIP_FIELD : null,
                relationship, "relationship");

        return new RecordList(study.id(), participantId, entity, rows.size(), maps(rows));
    }

    /**
     * Standalone and nested observations in one list. The two sources are disjoint records in the
     * corpus, so the total is a straight sum with no double counting.
     */
    public MeasurementList measurements(String studyId, String participantId, String concept, String visitId) {
        Study study = repository.study(studyId);
        study.participant(participantId);

        List<Observation> observations = observations(study, participantId).stream()
                .filter(observation -> blank(concept)
                        || concept.trim().equals(observation.observation().get("observation_type")))
                .filter(observation -> blank(visitId) || visitId.trim().equals(observation.visitId()))
                .toList();

        int standalone = (int) observations.stream().filter(o -> "standalone".equals(o.source())).count();
        return new MeasurementList(study.id(), participantId, observations.size(),
                standalone, observations.size() - standalone, observations);
    }

    /** One participant joined with all eight record types in the same study. */
    public ParticipantDetail details(String studyId, String participantId) {
        Study study = repository.study(studyId);
        JsonNode participant = study.participant(participantId);

        List<Map<String, Object>> demography = maps(study.recordsFor(participantId, "demography"));
        List<Map<String, Object>> conditions = maps(study.recordsFor(participantId, "conditions"));
        List<Map<String, Object>> visits = maps(study.recordsFor(participantId, "visits"));
        List<Map<String, Object>> drugExposures = maps(study.recordsFor(participantId, "drug-exposures"));
        List<JsonNode> sets = study.recordsFor(participantId, "measurement-sets");
        List<Observation> measurements = observations(study, participantId);

        int standalone = (int) measurements.stream().filter(o -> "standalone".equals(o.source())).count();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("demography", demography.size());
        counts.put("conditions", conditions.size());
        counts.put("visits", visits.size());
        counts.put("drugExposures", drugExposures.size());
        counts.put("measurementSets", sets.size());
        counts.put("standaloneMeasurements", standalone);
        counts.put("measurementSetObservations", measurements.size() - standalone);
        counts.put("measurements", measurements.size());

        return new ParticipantDetail(study.id(), map(participant),
                linkedPerson(study, participantId).map(this::map).orElse(null),
                demography, conditions, visits, drugExposures, measurements, maps(sets), counts);
    }

    // ----------------------------------------------------------------- joins

    private Optional<JsonNode> linkedPerson(Study study, String participantId) {
        String personId = study.participant(participantId).path("associated_person").asText("");
        if (personId.isBlank()) {
            return Optional.empty();
        }
        JsonNode person = study.personsById().get(personId);
        if (person == null) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                    "Participant " + participantId + " references a Person that is missing from " + study.id()
                            + ": " + personId);
        }
        return Optional.of(person);
    }

    private List<Observation> observations(Study study, String participantId) {
        List<Observation> result = new ArrayList<>();
        for (JsonNode row : study.recordsFor(participantId, "measurements")) {
            result.add(new Observation("standalone", null, text(row, "associated_visit"), map(row)));
        }
        for (JsonNode set : study.recordsFor(participantId, "measurement-sets")) {
            String setId = text(set, "id");
            for (JsonNode row : StudyRepository.observationsOf(study.id(), set)) {
                String owner = text(row, "associated_participant");
                if (owner != null && !owner.equals(participantId)) {
                    throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                            "Measurement set " + setId + " in " + study.id()
                                    + " holds an observation for a different participant: " + owner);
                }
                String visitId = text(row, "associated_visit");
                if (visitId == null) {
                    visitId = text(set, "associated_visit");
                }
                result.add(new Observation("measurement-set", setId, visitId, map(row)));
            }
        }
        return result;
    }

    // --------------------------------------------------------------- filters

    /** Exact, case-sensitive match. A blank value means no filter; an unsupported filter is a 400. */
    private static List<JsonNode> filter(List<JsonNode> rows, String entity, String field,
                                         String value, String parameter) {
        if (blank(value)) {
            return rows;
        }
        if (field == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "The " + parameter + " filter does not apply to " + entity);
        }
        String expected = value.trim();
        return rows.stream().filter(row -> expected.equals(row.path(field).asText(""))).toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(JsonNode row, String field) {
        String value = row.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private Map<String, Object> map(JsonNode row) {
        return mapper.convertValue(row, RECORD);
    }

    private List<Map<String, Object>> maps(List<JsonNode> rows) {
        return rows.stream().map(this::map).toList();
    }
}
