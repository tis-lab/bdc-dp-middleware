package org.biodatacatalyst.middleware.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Loads every study folder once at startup and keeps the parsed records in memory.
 *
 * <p>A study is a folder under {@code app.data-location}; its folder name is the study identifier.
 * Each YAML file in that folder is matched to an entity by the LinkML class in its file name, so
 * both {@code SYNTH1-Condition--data.yaml} and {@code Condition.yaml} load as {@code conditions}.
 * Records are indexed by identifier and by {@code associated_participant}, which keeps every join
 * inside a single study and avoids re-parsing ~45,000 YAML documents on each request.
 */
@Repository
public class StudyRepository {

    /** API entity key to LinkML class name, in the order used for study record counts. */
    private static final Map<String, String> ENTITY_TYPES = new LinkedHashMap<>();

    static {
        ENTITY_TYPES.put("persons", "Person");
        ENTITY_TYPES.put("participants", "Participant");
        ENTITY_TYPES.put("demography", "Demography");
        ENTITY_TYPES.put("conditions", "Condition");
        ENTITY_TYPES.put("visits", "Visit");
        ENTITY_TYPES.put("drug-exposures", "DrugExposure");
        ENTITY_TYPES.put("measurements", "MeasurementObservation");
        ENTITY_TYPES.put("measurement-sets", "MeasurementObservationSet");
    }

    /** Entities that reference a participant directly and are therefore indexed by participant. */
    private static final List<String> PARTICIPANT_LINKED = List.of(
            "demography", "conditions", "visits", "drug-exposures", "measurements", "measurement-sets");

    private static final Logger log = LoggerFactory.getLogger(StudyRepository.class);

    private final Map<String, Study> studies;

    public StudyRepository(ResourcePatternResolver resolver,
                           @Value("${app.data-location:classpath:data/}") String location) {
        this.studies = load(resolver, location.endsWith("/") ? location : location + "/");
    }

    /** All entity keys, in record-count order. */
    public static List<String> entityKeys() {
        return List.copyOf(ENTITY_TYPES.keySet());
    }

    public List<String> studyIds() {
        return List.copyOf(studies.keySet());
    }

    public Study study(String studyId) {
        Study study = studies.get(studyId);
        if (study == null) {
            throw new ResponseStatusException(NOT_FOUND,
                    "Unknown study: " + studyId + ". Known studies: " + studyIds());
        }
        return study;
    }

    /** One loaded study. All lists and maps are immutable. */
    public record Study(String id,
                        String name,
                        Map<String, List<JsonNode>> records,
                        Map<String, JsonNode> participantsById,
                        Map<String, JsonNode> personsById,
                        Map<String, Map<String, List<JsonNode>>> recordsByParticipant,
                        int embeddedObservationCount) {

        public List<JsonNode> records(String entity) {
            List<JsonNode> rows = records.get(entity);
            if (rows == null) {
                throw new ResponseStatusException(NOT_FOUND,
                        "Unknown entity: " + entity + ". Known entities: " + entityKeys());
            }
            return rows;
        }

        public JsonNode participant(String participantId) {
            JsonNode participant = participantsById.get(participantId);
            if (participant == null) {
                throw new ResponseStatusException(NOT_FOUND,
                        "Unknown participant in " + id + ": " + participantId);
            }
            return participant;
        }

        /** Records of one entity that reference the given participant, in file order. */
        public List<JsonNode> recordsFor(String participantId, String entity) {
            records(entity);
            Map<String, List<JsonNode>> index = recordsByParticipant.get(entity);
            if (index == null) {
                throw new ResponseStatusException(NOT_FOUND,
                        "Entity " + entity + " is not linked to a participant. Linked entities: "
                                + PARTICIPANT_LINKED);
            }
            return index.getOrDefault(participantId, List.of());
        }

        public int participantCount() {
            return participantsById.size();
        }

        public Map<String, Integer> recordCounts() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            records.forEach((entity, rows) -> counts.put(entity, rows.size()));
            return counts;
        }
    }

    // ---------------------------------------------------------------- loading

    private static Map<String, Study> load(ResourcePatternResolver resolver, String location) {
        Map<String, Map<String, Resource>> byStudy = discover(resolver, location);
        if (byStudy.isEmpty()) {
            throw new IllegalStateException("No study folders with YAML files found under " + location
                    + ". Expected one folder per study, for example " + location + "study_one/"
                    + "SYNTH1-Participant--data.yaml. Set APP_DATA_LOCATION to override.");
        }
        Map<String, Study> loaded = new LinkedHashMap<>();
        byStudy.forEach((studyId, files) -> loaded.put(studyId, read(studyId, files)));
        loaded.values().forEach(study -> log.info("Loaded study {} ({}): {} records, {} embedded observations",
                study.id(), study.name(), study.recordCounts(), study.embeddedObservationCount()));
        // Insertion order is kept so study and entity listings are stable; Map.copyOf would not keep it.
        return Collections.unmodifiableMap(loaded);
    }

    /** Finds study folders and maps each file to an entity key. Sorted so output order is stable. */
    private static Map<String, Map<String, Resource>> discover(ResourcePatternResolver resolver, String location) {
        Map<String, Map<String, Resource>> byStudy = new TreeMap<>();
        Map<String, String> entityByType = new HashMap<>();
        ENTITY_TYPES.forEach((entity, type) -> entityByType.put(type, entity));
        for (String pattern : List.of(location + "*/*.yaml", location + "*/*.yml")) {
            Resource[] found;
            try {
                found = resolver.getResources(pattern);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to list study data under " + pattern, exception);
            }
            for (Resource resource : found) {
                String fileName = fileName(resource);
                String entity = entityByType.get(linkmlType(fileName));
                if (entity == null) {
                    log.warn("Ignoring {}: its name does not end with a known record type {}",
                            fileName, entityByType.keySet());
                    continue;
                }
                String studyId = folderName(resource);
                Resource clash = byStudy.computeIfAbsent(studyId, key -> new LinkedHashMap<>())
                        .put(entity, resource);
                if (clash != null) {
                    throw new IllegalStateException("Study " + studyId + " has two files for " + entity + ": "
                            + fileName(clash) + " and " + fileName);
                }
            }
        }
        return byStudy;
    }

    private static Study read(String studyId, Map<String, Resource> files) {
        Map<String, List<JsonNode>> records = new LinkedHashMap<>();
        for (String entity : ENTITY_TYPES.keySet()) {
            Resource file = files.get(entity);
            if (file == null) {
                throw new IllegalStateException("Study " + studyId + " has no file for " + entity
                        + " (" + ENTITY_TYPES.get(entity) + "). Expected all of " + entityKeys());
            }
            records.put(entity, List.copyOf(parse(studyId, entity, file)));
        }

        Map<String, JsonNode> participantsById = index(studyId, "participants", records.get("participants"));
        Map<String, JsonNode> personsById = index(studyId, "persons", records.get("persons"));

        Map<String, Map<String, List<JsonNode>>> byParticipant = new LinkedHashMap<>();
        for (String entity : PARTICIPANT_LINKED) {
            byParticipant.put(entity, groupByParticipant(records.get(entity)));
        }

        int embedded = 0;
        for (JsonNode set : records.get("measurement-sets")) {
            embedded += observationsOf(studyId, set).size();
        }

        return new Study(studyId, studyName(studyId, records.get("participants")),
                Collections.unmodifiableMap(records), participantsById, personsById,
                Collections.unmodifiableMap(byParticipant), embedded);
    }

    private static List<JsonNode> parse(String studyId, String entity, Resource file) {
        ObjectReader reader = YAMLMapper.builder()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .build()
                .readerFor(JsonNode.class);
        List<JsonNode> records = new ArrayList<>();
        try (InputStream input = file.getInputStream();
             MappingIterator<JsonNode> documents = reader.readValues(input)) {
            while (documents.hasNextValue()) {
                JsonNode document = documents.nextValue();
                if (document == null || document.isNull()) {
                    continue;
                }
                if (document.isArray()) {
                    document.forEach(records::add);
                } else {
                    records.add(document);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + fileName(file)
                    + " for " + studyId + "/" + entity, exception);
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode record : records) {
            if (!record.isObject()) {
                throw new IllegalStateException("Expected a YAML object per record in "
                        + fileName(file) + " but found " + record.getNodeType());
            }
            String id = record.path("id").asText("");
            if (id.isBlank()) {
                throw new IllegalStateException("Record without an id in " + fileName(file));
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("Duplicate id " + id + " in " + fileName(file));
            }
        }
        return records;
    }

    private static Map<String, JsonNode> index(String studyId, String entity, List<JsonNode> rows) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            byId.put(row.path("id").asText(), row);
        }
        log.debug("Indexed {} {} for {}", byId.size(), entity, studyId);
        return Collections.unmodifiableMap(byId);
    }

    private static Map<String, List<JsonNode>> groupByParticipant(List<JsonNode> rows) {
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String participantId = row.path("associated_participant").asText("");
            if (!participantId.isBlank()) {
                grouped.computeIfAbsent(participantId, key -> new ArrayList<>()).add(row);
            }
        }
        grouped.replaceAll((participantId, rowsForParticipant) -> List.copyOf(rowsForParticipant));
        return Collections.unmodifiableMap(grouped);
    }

    /** The observations nested in a measurement set; an empty list when the field is absent or null. */
    public static List<JsonNode> observationsOf(String studyId, JsonNode set) {
        JsonNode nested = set.path("observations");
        if (nested.isMissingNode() || nested.isNull()) {
            return List.of();
        }
        if (!nested.isArray()) {
            throw new IllegalStateException("observations must be a list in " + studyId
                    + " measurement set " + set.path("id").asText());
        }
        List<JsonNode> observations = new ArrayList<>();
        for (JsonNode observation : nested) {
            if (!observation.isObject()) {
                throw new IllegalStateException("Nested observation must be an object in " + studyId
                        + " measurement set " + set.path("id").asText());
            }
            observations.add(observation);
        }
        return observations;
    }

    private static String studyName(String studyId, List<JsonNode> participants) {
        return participants.stream()
                .map(participant -> participant.path("member_of_research_study").asText(""))
                .filter(name -> !name.isBlank())
                .findFirst()
                .orElse(studyId);
    }

    // ------------------------------------------------------------ file naming

    private static String fileName(Resource resource) {
        String name = resource.getFilename();
        return name == null ? resource.getDescription() : name;
    }

    /** Folder that holds the file, which is the study identifier. */
    private static String folderName(Resource resource) {
        String path;
        try {
            path = resource.getURL().getPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to resolve " + resource.getDescription(), exception);
        }
        String[] segments = path.split("/");
        if (segments.length < 2) {
            throw new IllegalStateException("Data file " + path + " is not inside a study folder");
        }
        return segments[segments.length - 2];
    }

    /**
     * The LinkML class in a data file name: {@code SYNTH1-Condition--data.yaml} and {@code Condition.yaml}
     * both give {@code Condition}.
     */
    private static String linkmlType(String fileName) {
        String name = fileName.replaceFirst("\\.(yaml|yml)$", "");
        if (name.endsWith("--data")) {
            name = name.substring(0, name.length() - "--data".length());
        }
        int dash = name.lastIndexOf('-');
        return dash < 0 ? name : name.substring(dash + 1);
    }
}
