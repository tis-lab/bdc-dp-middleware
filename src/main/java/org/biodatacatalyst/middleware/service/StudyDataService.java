package org.biodatacatalyst.middleware.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class StudyDataService {
    private static final Map<String, String> STUDIES = Map.of(
            "study_one", "SYNTH1", "study_two", "SYNTH2");
    private static final Map<String, String> ENTITIES = Map.of(
            "persons", "Person", "participants", "Participant",
            "demography", "Demography", "visits", "Visit",
            "conditions", "Condition", "drug-exposures", "DrugExposure",
            "measurements", "MeasurementObservation",
            "measurement-sets", "MeasurementObservationSet");

    private final ResourceLoader resources;
    private final String location;
    private final ObjectReader reader = YAMLMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build().readerFor(JsonNode.class);

    public StudyDataService(ResourceLoader resources,
            @Value("${app.data-location:classpath:data/}") String location) {
        this.resources = resources;
        this.location = location.endsWith("/") ? location : location + "/";
    }

    public List<String> studies() {
        return STUDIES.keySet().stream().sorted().toList();
    }

    public List<String> entities(String studyId) {
        checkStudy(studyId);
        return ENTITIES.keySet().stream().sorted().toList();
    }

    public List<JsonNode> read(String studyId, String entity) {
        checkStudy(studyId);
        String type = ENTITIES.get(entity);
        if (type == null) throw new ResponseStatusException(NOT_FOUND, "Unknown entity: " + entity);

        String folder = location + studyId + "/";
        Resource original = resources.getResource(folder + STUDIES.get(studyId) + "-" + type + "--data.yaml");
        Resource renamed = resources.getResource(folder + type + ".yaml");
        if (original.exists() && renamed.exists()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Two YAML files found for " + studyId + "/" + entity);
        }
        Resource file = original.exists() ? original : renamed;
        List<JsonNode> records = new ArrayList<>();
        try (var input = file.getInputStream(); MappingIterator<JsonNode> documents = reader.readValues(input)) {
            while (documents.hasNextValue()) {
                JsonNode document = documents.nextValue();
                if (document == null || document.isNull()) continue;
                if (document.isArray()) {
                    for (JsonNode record : document) addRecord(records, record);
                } else {
                    addRecord(records, document);
                }
            }
            return records;
        } catch (IOException exception) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                    "Unable to read YAML for " + studyId + "/" + entity, exception);
        }
    }

    private static void checkStudy(String studyId) {
        if (!STUDIES.containsKey(studyId)) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown study: " + studyId);
        }
    }

    private static void addRecord(List<JsonNode> records, JsonNode record) throws IOException {
        if (!record.isObject()) throw new IOException("Expected a YAML object for each record");
        records.add(record);
    }
}
