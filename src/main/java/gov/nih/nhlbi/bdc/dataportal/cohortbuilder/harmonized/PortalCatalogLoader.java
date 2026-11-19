package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.harmonized;

import java.io.IOException;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads a pinned, self-contained catalog collection. Full LinkML validation runs in CI. */
final class PortalCatalogLoader {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> VALUE_SLOTS = Set.of(
            "condition_status", "exposure_status", "procedure_status", "measurement_value");
    private final ResourceLoader resources;

    PortalCatalogLoader(ResourceLoader resources) { this.resources = resources; }

    List<BdcConcept> load(String location) {
        Resource manifestResource = resources.getResource(location);
        JsonNode manifest = parse(bytes(manifestResource));
        require(manifest.path("schema_version").asInt() == 3, "Expected version-3 catalog manifest: " + location);
        String release = required(manifest, "release");
        var entries = array(manifest, "catalogs");
        require(!entries.isEmpty(), "Catalog manifest is empty");
        Map<String, Card> cards = new TreeMap<>();
        Set<String> files = new HashSet<>(), prefixes = new HashSet<>(), variableIds = new HashSet<>();
        for (var entry : entries) {
            String filename = required(entry, "file");
            require(filename.matches("varlib_catalog_[A-Za-z0-9_-]+\\.json") && files.add(filename),
                    "Invalid or duplicate catalog filename: " + filename);
            byte[] content;
            try { content = bytes(manifestResource.createRelative(filename)); }
            catch (IOException error) { throw new IllegalStateException("Cannot resolve catalog " + filename, error); }
            JsonNode catalog = parse(content);
            require(catalog.path("schema_version").asInt() == 3 && release.equals(required(catalog, "release")),
                    "Catalog schema/release mismatch: " + filename);
            String prefix = required(entry, "prefix");
            require(prefixes.add(prefix), "Duplicate catalog prefix: " + prefix);
            var concepts = index(catalog, "concepts", "curie");
            var studies = index(catalog, "studies", "id");
            var compounds = index(catalog, "compound_variables", "id");
            var sources = index(catalog, "sources", "source_id");
            var singles = indexOptional(catalog, "single_continuous_variables", "id");
            indexOptional(catalog, "single_categorical_variables", "id").forEach((id, row) -> require(singles.putIfAbsent(id, row) == null, "Duplicate source variable " + id));
            for (var concept : concepts.values()) {
                String curie = required(concept, "curie");
                require(curie.startsWith(prefix + ":"), "Incorrect prefix partition: " + curie);
            }
            var integrated = array(catalog, "integrated_variables");
            require(!integrated.isEmpty(), "Catalog has no integrated variables: " + filename);
            for (var iv : integrated) {
                String id = required(iv, "id"), curie = required(iv, "concept_type");
                require(variableIds.add(id), "Duplicate integrated-variable identity: " + id);
                var concept = resolve(concepts, curie);
                var type = BdchmTypeEnum.fromIndex(required(iv, "bdchm_type"));
                var card = cards.computeIfAbsent(curie, k -> new Card(curie, optional(concept, "label")));
                card.types.add(type);
                String cls = className(iv, type), slot = conceptSlot(type);
                card.classes.add(cls);
                List<BdcConcept.StudyMapping> mappings = new ArrayList<>();
                var refs = strings(iv, "integrates");
                require(!refs.isEmpty() && new HashSet<>(refs).size() == refs.size(), "Invalid integrates: " + id);
                for (String ref : refs) {
                    var compound = resolve(compounds, ref);
                    require(curie.equals(required(compound, "concept_type"))
                                    && type.name().equals(required(compound, "bdchm_type")),
                            "Integrated/compound concept or class mismatch: " + ref);
                    String studyId = required(compound, "associated_study");
                    String studyName = required(resolve(studies, studyId), "study_name");
                    card.studies.put(studyId, studyName);
                    List<BdcConcept.SourceVariable> variables = new ArrayList<>();
                    for (var row : optionalArray(compound, "row_metadata")) {
                        String sourceId = required(row, "id");
                        var source = resolve(singles, sourceId);
                        require(studyId.equals(required(source, "associated_study")), "Source study mismatch: " + sourceId);
                        List<String> slots = optionalStrings(row);
                        String name = required(source, "variable_name");
                        variables.add(new BdcConcept.SourceVariable(sourceId, name,
                                optional(source, "source_variable_description"), optional(source, "data_type"),
                                optional(source, "unit"), optional(source, "file_id"),
                                name.equals(optional(source, "source_id")),
                                slots.stream().anyMatch(VALUE_SLOTS::contains), slots));
                    }
                    mappings.add(new BdcConcept.StudyMapping(ref, required(compound, "variable_label"),
                            optional(compound, "variable_description"), studyId, studyName, List.copyOf(variables)));
                    for (String sourceId : strings(compound, "metadata_sources")) {
                        String path = required(resolve(sources, sourceId), "path");
                        card.sourceMappings.add(new BdcConcept.SourceMapping(studyName, cls, slot, path));
                        // The legacy alias field retains its documented file-stem meaning.
                        String alias = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.ya?ml$", "");
                        card.aliases.computeIfAbsent(alias, k -> new TreeSet<>()).add(studyName);
                    }
                }
                card.variables.add(new BdcConcept.IntegratedVariable(id, required(iv, "variable_label"),
                        optional(iv, "variable_description"), type, List.copyOf(mappings)));
            }
        }
        return cards.values().stream().map(Card::build).toList();
    }

    private static final class Card {
        final String curie, label;
        final Set<BdchmTypeEnum> types = new HashSet<>();
        final Set<String> classes = new TreeSet<>();
        final Map<String, String> studies = new TreeMap<>();
        final Map<String, Set<String>> aliases = new TreeMap<>();
        final Set<BdcConcept.SourceMapping> sourceMappings = new LinkedHashSet<>();
        final List<BdcConcept.IntegratedVariable> variables = new ArrayList<>();
        Card(String curie, String label) { this.curie = curie; this.label = label; }
        BdcConcept build() {
            return new BdcConcept(curie, label, types.size() == 1 ? types.iterator().next() : null,
                    List.copyOf(classes), List.copyOf(sourceMappings), studies.values().stream().sorted().toList(),
                    aliases.entrySet().stream().map(e -> new BdcConcept.BdcCarrier(e.getKey(), null, List.copyOf(e.getValue()))).toList(),
                    List.copyOf(variables));
        }
    }

    private static String className(JsonNode variable, BdchmTypeEnum type) {
        // PR 12 explicitly defines integrated IDs as bdchm:<Class>/<CURIE>. The coarse
        // observation enum alone cannot distinguish Observation from MeasurementObservation.
        String id = required(variable, "id");
        String suffix = "/" + required(variable, "concept_type");
        require(id.startsWith("bdchm:") && id.endsWith(suffix), "Unsupported producer ID convention: " + id);
        String cls = id.substring("bdchm:".length(), id.length() - suffix.length());
        Set<String> allowed = switch (type) {
            case condition -> Set.of("Condition");
            case observation -> Set.of("Observation", "MeasurementObservation", "SdohObservation");
            case procedure -> Set.of("Procedure");
            case drugexposure -> Set.of("DrugExposure");
            case exposure -> Set.of();
        };
        require(allowed.contains(cls), "Unsupported or inconsistent producer class: " + id);
        return cls;
    }
    private static String conceptSlot(BdchmTypeEnum type) {
        return switch (type) {
            case condition -> "condition_concept";
            case observation -> "observation_type";
            case procedure -> "procedure_concept";
            case drugexposure -> "drug_concept";
            case exposure -> throw new IllegalStateException("No exact Exposure concept-slot mapping is defined");
        };
    }
    private static Map<String, JsonNode> index(JsonNode root, String group, String key) {
        array(root, group);
        return indexOptional(root, group, key);
    }
    private static Map<String, JsonNode> indexOptional(JsonNode root, String group, String key) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (var row : optionalArray(root, group)) {
            String id = required(row, key);
            require(result.putIfAbsent(id, row) == null, "Duplicate " + group + " identity: " + id);
        }
        return result;
    }
    private static JsonNode resolve(Map<String, JsonNode> map, String id) {
        var row = map.get(id);
        require(row != null, "Unresolved catalog reference: " + id);
        return row;
    }
    private static List<JsonNode> array(JsonNode root, String field) {
        require(root.path(field).isArray(), "Expected catalog array: " + field);
        List<JsonNode> result = new ArrayList<>();
        root.path(field).forEach(result::add);
        return result;
    }
    private static List<JsonNode> optionalArray(JsonNode root, String field) {
        return root.has(field) ? array(root, field) : List.of();
    }
    private static List<String> strings(JsonNode root, String field) {
        return array(root, field).stream().map(n -> {
            require(n.isString() && !n.asString().isBlank(), "Expected reference string: " + field);
            return n.asString();
        }).toList();
    }
    private static List<String> optionalStrings(JsonNode root) {
        return root.has("microschema_slot") ? strings(root, "microschema_slot") : List.of();
    }
    private static String required(JsonNode row, String field) {
        String value = optional(row, field);
        require(value != null, "Missing catalog field: " + field);
        return value;
    }
    private static String optional(JsonNode row, String field) {
        var value = row.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
    private static byte[] bytes(Resource resource) {
        try (var stream = resource.getInputStream()) { return stream.readAllBytes(); }
        catch (IOException error) { throw new IllegalStateException("Cannot read VarLib catalog: " + resource, error); }
    }
    private static JsonNode parse(byte[] bytes) {
        try {
            JsonNode root = JSON.readTree(bytes);
            require(root != null && root.isObject(), "Expected a catalog JSON object");
            return root;
        }
        catch (RuntimeException error) { throw new IllegalStateException("Invalid VarLib JSON", error); }
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
