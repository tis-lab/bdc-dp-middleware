package org.biodatacatalyst.middleware.harmonized;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HarmonizedVariableLibrary {

    private static final Logger log = LoggerFactory.getLogger(HarmonizedVariableLibrary.class);

    /** A CURIE such as MONDO:0005044. Full-match only, so prose cannot be read as an identifier. */
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

    private final Map<String, BdcConcept> byConcept;

    public HarmonizedVariableLibrary(HarmonizedVariableProperties properties,
                                     ResourceLoader resourceLoader) {
        this.enabled = properties.enabled();
        this.maxMatches = properties.maxMatches();

        if (!enabled) {
            log.info("Harmonized concepts disabled; every concept reports zero coverage");
            this.searchable = List.of();
            this.byConcept = Map.of();
            return;
        }

        JsonNode index = read(resourceLoader.getResource(properties.index()));
        List<BdcConcept> concepts = concepts(index.path("concepts"));

        this.searchable = concepts.stream()
                .sorted(Comparator.comparingInt(BdcConcept::studyCount).reversed()
                        .thenComparing(BdcConcept::conceptType))
                .map(concept -> new Indexed(concept, searchTokens(concept)))
                .toList();

        Map<String, BdcConcept> keyed = new HashMap<>(concepts.size());
        concepts.forEach(concept -> keyed.put(conceptKey(concept.conceptType()), concept));
        this.byConcept = Collections.unmodifiableMap(keyed);

        log.info("Loaded harmonized index generated {}: {} concepts, {} with a label",
                index.path("generated").asString("unknown"), concepts.size(),
                concepts.stream().filter(c -> c.conceptLabel() != null).count());
    }

    /** How many concepts are covered. Zero when the feature is switched off. */
    public int conceptCount() {
        return byConcept.size();
    }

    public BdcCoverage coverageFor(String conceptId) {
        if (!enabled || conceptId == null || conceptId.isBlank()) {
            return BdcCoverage.none();
        }
        BdcConcept concept = byConcept.get(conceptKey(conceptId.strip()));
        return concept == null ? BdcCoverage.none() : BdcCoverage.of(concept);
    }

    public List<BdcConcept> search(String query) {
        if (searchable.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        String trimmed = query.strip();
        if (CURIE.matcher(trimmed).matches()) {
            BdcConcept exact = byConcept.get(conceptKey(trimmed));
            // An unknown identifier must not fall through to fuzzy text search.
            return exact == null ? List.of() : List.of(exact);
        }

        Set<String> queryTokens = tokenize(trimmed);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        List<BdcConcept> matches = new ArrayList<>();
        for (Indexed candidate : searchable) {
            if (matchesText(queryTokens, candidate.tokens())) {
                matches.add(candidate.concept());
                if (matches.size() == maxMatches) {
                    // Pre-sorted by coverage, so stopping early keeps the best matches.
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    // ------------------------------------------------------------------- text matching
    private static boolean matchesText(Set<String> queryTokens, Set<String> conceptTokens) {
        for (String queryToken : queryTokens) {
            boolean covered = false;
            for (String conceptToken : conceptTokens) {
                if (queryToken.equals(conceptToken) || isPrefixMatch(queryToken, conceptToken)) {
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

    private static Set<String> searchTokens(BdcConcept concept) {
        Set<String> tokens = new LinkedHashSet<>();
        if (concept.conceptLabel() != null) {
            tokens.addAll(tokenize(concept.conceptLabel()));
        }
        for (BdcConcept.BdcCarrier carrier : concept.variables()) {
            tokens.addAll(tokenize(carrier.variableName()));
            if (carrier.variableLabel() != null) {
                tokens.addAll(tokenize(carrier.variableLabel()));
            }
        }
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

    private record Indexed(BdcConcept concept, Set<String> tokens) {
    }

    // ---------------------------------------------------------------------------- loading

    private static JsonNode read(Resource resource) {
        JsonNode index;
        try (InputStream input = resource.getInputStream()) {
            index = JsonMapper.builder().build().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read the harmonized index from "
                    + resource.getDescription() + ". Regenerate it with "
                    + "tools/build_harmonized_index.py, or set harmonized.enabled=false to start "
                    + "without it.", exception);
        } catch (JacksonException exception) {
            // Jackson 3 makes parse failures unchecked; without this the message would not name
            // the file.
            throw new IllegalStateException("Unable to parse the harmonized index at "
                    + resource.getDescription(), exception);
        }
        if (index == null || !index.path("concepts").isArray()) {
            throw new IllegalStateException("The harmonized index at " + resource.getDescription()
                    + " has no concepts array; regenerate with tools/build_harmonized_index.py");
        }
        if (index.path("schemaVersion").asInt() != 2) {
            throw new IllegalStateException("The harmonized index at " + resource.getDescription()
                    + " is not a version 2 concept index; regenerate it with "
                    + "tools/build_harmonized_index.py");
        }
        return index;
    }

    private static List<BdcConcept> concepts(JsonNode rows) {
        List<BdcConcept> concepts = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            List<BdcConcept.BdcCarrier> carriers = new ArrayList<>();
            for (JsonNode carrier : row.path("variables")) {
                carriers.add(new BdcConcept.BdcCarrier(
                        required(carrier, "variableName"),
                        optional(carrier, "variableLabel"),
                        strings(carrier.path("studies"))));
            }
            List<BdcConcept.SourceMapping> mappings = new ArrayList<>();
            for (JsonNode mapping : row.path("sourceMappings")) {
                mappings.add(new BdcConcept.SourceMapping(
                        required(mapping, "study"), required(mapping, "bdchmClass"),
                        required(mapping, "conceptSlot"), required(mapping, "sourceFile")));
            }
            concepts.add(new BdcConcept(
                    required(row, "conceptType"),
                    optional(row, "conceptLabel"),
                    BdchmTypeEnum.fromIndex(optional(row, "bdchmType")),
                    strings(row.path("bdchmClasses")),
                    List.copyOf(mappings),
                    strings(row.path("studies")),
                    List.copyOf(carriers)));
        }
        return List.copyOf(concepts);
    }

    private static String required(JsonNode row, String field) {
        String value = optional(row, field);
        if (value == null) {
            throw new IllegalStateException(
                    "The harmonized index has a row with no '" + field + "'; regenerate it");
        }
        return value;
    }

    private static String optional(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        node.forEach(element -> {
            if (element.isString()) {
                values.add(element.stringValue());
            }
        });
        return List.copyOf(values);
    }

    /** Convenience for tests and for callers without an injected loader. */
    public static HarmonizedVariableLibrary bundled(HarmonizedVariableProperties properties) {
        return new HarmonizedVariableLibrary(properties,
                new org.springframework.core.io.DefaultResourceLoader());
    }

    /** A library that loads nothing, for tests of unrelated behaviour. */
    public static HarmonizedVariableLibrary disabled() {
        return bundled(new HarmonizedVariableProperties(false, "", 25));
    }
}
