package org.biodatacatalyst.middleware.harmonized;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

@Component
public class HarmonizedVariableLibrary {

    private static final Logger log = LoggerFactory.getLogger(HarmonizedVariableLibrary.class);

    private static final Pattern CONCEPT_SLOT = Pattern.compile(".*_concept|observation_type");

    private static final Pattern CURIE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*:[A-Za-z0-9._-]+");

    private static final int MIN_PREFIX_LENGTH = 5;

    private static String conceptKey(String curie) {
        int colon = curie.indexOf(':');
        return colon < 0 ? curie
                : curie.substring(0, colon).toUpperCase(Locale.ROOT) + curie.substring(colon);
    }

    private final boolean enabled;
    private final int maxMatches;

    private final List<Indexed> searchable;

    private final Map<String, BdcCoverage> byConcept;

    public HarmonizedVariableLibrary(HarmonizedVariableProperties properties,
                                     ResourcePatternResolver resourceResolver) {
        this.enabled = properties.enabled();
        this.maxMatches = properties.maxMatches();

        if (!enabled) {
            log.info("Harmonized variables disabled; every concept reports zero coverage");
            this.searchable = List.of();
            this.byConcept = Map.of();
            return;
        }

        Map<String, Draft> drafts = readIngestSpecs(resourceResolver, properties.ingest());
        Map<String, String[]> table = readVariableTable(resourceResolver, properties.variableTable());
        List<BdcVariable> variables = assemble(drafts, table);

        this.searchable = variables.stream()
                .sorted(Comparator.comparingInt(BdcVariable::studyCount).reversed()
                        .thenComparing(BdcVariable::name))
                .map(variable -> new Indexed(variable, searchTokens(variable)))
                .toList();
        this.byConcept = index(drafts);

        log.info("Indexed {} harmonized variables under {} concepts ({} with a label)",
                variables.size(), byConcept.size(),
                variables.stream().filter(v -> !v.label().equals(v.name())).count());
    }

    /** How many variables are loaded. Zero when the feature is switched off. */
    public int variableCount() {
        return searchable.size();
    }

    /** How many distinct concepts are covered. */
    public int conceptCount() {
        return byConcept.size();
    }

    public BdcCoverage coverageFor(String conceptId) {
        if (!enabled || conceptId == null || conceptId.isBlank()) {
            return BdcCoverage.none();
        }
        return byConcept.getOrDefault(conceptKey(conceptId.strip()), BdcCoverage.none());
    }

    public List<BdcVariable> search(String query) {
        if (searchable.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        String trimmed = query.strip();
        if (CURIE.matcher(trimmed).matches()) {
            BdcCoverage coverage = coverageFor(trimmed);
            // Unknown identifiers must not fall through to fuzzy text search.
            return capped(searchable.stream()
                    .map(Indexed::variable)
                    .filter(variable -> coverage.variableNames().contains(variable.name()))
                    .toList());
        }

        Set<String> queryTokens = tokenize(trimmed);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        List<BdcVariable> matches = new ArrayList<>();
        for (Indexed candidate : searchable) {
            if (matchesText(queryTokens, candidate.tokens())) {
                matches.add(candidate.variable());
                if (matches.size() == maxMatches) {
                    // Pre-sorted by coverage, so stopping early keeps the best matches.
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    private List<BdcVariable> capped(List<BdcVariable> matches) {
        return matches.size() <= maxMatches ? List.copyOf(matches)
                : List.copyOf(matches.subList(0, maxMatches));
    }

    private static Map<String, BdcCoverage> index(Map<String, Draft> drafts) {
        Map<String, Set<String>> studies = new HashMap<>();
        Map<String, Set<String>> names = new HashMap<>();
        drafts.forEach((name, draft) -> draft.studiesByConcept.forEach((concept, assigning) -> {
            if (assigning.isEmpty()) {
                return;
            }
            String key = conceptKey(concept);
            studies.computeIfAbsent(key, _ -> new TreeSet<>()).addAll(assigning);
            names.computeIfAbsent(key, _ -> new TreeSet<>()).add(name);
        }));

        Map<String, BdcCoverage> coverage = new HashMap<>(studies.size());
        studies.forEach((key, assigning) -> coverage.put(key,
                new BdcCoverage(assigning.size(), List.copyOf(assigning),
                        List.copyOf(names.getOrDefault(key, Set.of())))));
        return Collections.unmodifiableMap(coverage);
    }

    // ------------------------------------------------------------------- text matching
    private static boolean matchesText(Set<String> queryTokens, Set<String> variableTokens) {
        for (String queryToken : queryTokens) {
            boolean covered = false;
            for (String variableToken : variableTokens) {
                if (queryToken.equals(variableToken) || isPrefixMatch(queryToken, variableToken)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrefixMatch(String a, String b) {
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= MIN_PREFIX_LENGTH && longer.startsWith(shorter);
    }

    private static Set<String> searchTokens(BdcVariable variable) {
        Set<String> tokens = new LinkedHashSet<>(tokenize(variable.name()));
        tokens.addAll(tokenize(variable.label()));
        return tokens;
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() > 1) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** A variable with its searchable text pre-tokenized. */
    private record Indexed(BdcVariable variable, Set<String> tokens) {
    }

    // ---------------------------------------------------------------------------- loading

    private static final class Draft {
        final Set<String> studies = new TreeSet<>();
        final Map<String, Set<String>> studiesByConcept = new TreeMap<>();
        final Set<String> concepts = new TreeSet<>();
    }

    private static Map<String, Draft> readIngestSpecs(ResourcePatternResolver resolver, String pattern) {
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to list the harmonized ingest specs. Set harmonized.enabled=false to "
                            + "start without them.", exception);
        }
        if (resources.length == 0) {
            throw new IllegalStateException(
                    "No harmonized ingest specs matched " + pattern + ". Expected "
                            + "<STUDY>-ingest/<variable>.yaml. Set harmonized.enabled=false to start "
                            + "without them.");
        }

        YAMLMapper mapper = YAMLMapper.builder().build();
        Map<String, Draft> drafts = new TreeMap<>();
        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            if (fileName == null) {
                continue;
            }
            String variableName = fileName.replaceFirst("\\.ya?ml$", "");
            String study = studyOf(resource);

            JsonNode specs;
            try (InputStream input = resource.getInputStream()) {
                specs = mapper.readTree(input);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Unable to read harmonized ingest spec " + resource.getDescription(), exception);
            } catch (JacksonException exception) {
                // Jackson 3 makes parse failures unchecked; without this the message would not say
                // which of the 826 files was malformed.
                throw new IllegalStateException(
                        "Unable to parse harmonized ingest spec " + resource.getDescription(), exception);
            }
            if (specs == null || !specs.isArray()) {
                // One file upstream is a stub with no derivations. Skipping it is correct; failing
                // would make this service hostage to an unrelated file in someone else's repository.
                log.debug("Skipping {}: not a list of class derivations", resource.getDescription());
                continue;
            }

            Draft draft = drafts.computeIfAbsent(variableName, _ -> new Draft());
            draft.studies.add(study);
            specs.forEach(spec -> collect(spec.path("class_derivations"), draft, study));
        }
        return drafts;
    }

    private static void collect(JsonNode classDerivations, Draft draft, String study) {
        if (!classDerivations.isObject()) {
            return;
        }
        classDerivations.properties().forEach(classEntry -> {
            JsonNode body = classEntry.getValue();
            body.path("slot_derivations").properties().forEach(slotEntry -> {
                JsonNode slot = slotEntry.getValue();
                if (!slot.isObject()) {
                    return;
                }
                if (CONCEPT_SLOT.matcher(slotEntry.getKey()).matches()) {
                    String value = slot.path("value").asString("");
                    // The http(s) guard matters: some slots carry a full URI, which satisfies the
                    // CURIE shape but is not a concept identifier.
                    if (CURIE.matcher(value).matches() && !value.regionMatches(true, 0, "http", 0, 4)) {
                        draft.concepts.add(value);
                        draft.studiesByConcept.computeIfAbsent(value, _ -> new TreeSet<>()).add(study);
                    }
                }
            });
        });
    }

    private static String studyOf(Resource resource) {
        String path;
        try {
            path = resource.getURL().getPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to resolve " + resource.getDescription(), exception);
        }
        String[] segments = path.split("/");
        if (segments.length < 2) {
            throw new IllegalStateException("Ingest spec is not inside a study directory: " + path);
        }
        return segments[segments.length - 2].replaceFirst("-ingest$", "");
    }


    private static Map<String, String[]> readVariableTable(ResourcePatternResolver resolver,
                                                           String location) {
        Resource resource = resolver.getResource(location);
        if (!resource.exists()) {
            log.warn("Harmonized variable table not found at {}; variables will have no labels",
                    resource.getDescription());
            return Map.of();
        }

        Map<String, String[]> table = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return Map.of();
            }

            List<String> header = List.of(headerLine.split("\t", -1));
            int name = header.indexOf("var_name");

            int label = header.indexOf("var_label");
            int domain = header.indexOf("DOMAIN");
            if (name < 0) {
                log.warn("Harmonized variable table has no var_name column; ignoring it");
                return Map.of();
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split("\t", -1);
                String key = cell(cells, name);
                if (key == null) {
                    continue;
                }
                table.put(key, new String[]{cell(cells, label), cell(cells, domain)});
            }
        } catch (IOException exception) {
            log.warn("Unable to read the harmonized variable table; variables will have no labels",
                    exception);
            return Map.of();
        }
        return table;
    }

    private static List<BdcVariable> assemble(Map<String, Draft> drafts,
                                              Map<String, String[]> table) {
        List<BdcVariable> assembled = new ArrayList<>(drafts.size());
        drafts.forEach((name, draft) -> {
            String[] extra = table.getOrDefault(name, new String[2]);
            assembled.add(new BdcVariable(
                    name,
                    // Never null, so the frontend never has to choose between two name fields.
                    extra[0] != null ? extra[0] : name,
                    extra[1],
                    List.copyOf(draft.studies),
                    List.copyOf(draft.concepts)));
        });
        return List.copyOf(assembled);
    }

    private static String cell(String[] cells, int index) {
        if (index < 0 || index >= cells.length) {
            return null;
        }
        String value = cells[index].strip();
        return value.isEmpty() ? null : value;
    }

    public static HarmonizedVariableLibrary bundled(HarmonizedVariableProperties properties) {
        return new HarmonizedVariableLibrary(properties, new PathMatchingResourcePatternResolver());
    }

    public static HarmonizedVariableLibrary disabled() {
        return bundled(new HarmonizedVariableProperties(false, "", "", 25));
    }
}
