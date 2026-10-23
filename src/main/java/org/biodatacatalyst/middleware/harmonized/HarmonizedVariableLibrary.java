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

    private final Map<String, BdcCoverage> byConcept;

    public HarmonizedVariableLibrary(HarmonizedVariableProperties properties,
                                     ResourceLoader resourceLoader) {
        this.enabled = properties.enabled();
        this.maxMatches = properties.maxMatches();

        if (!enabled) {
            log.info("Harmonized variables disabled; every concept reports zero coverage");
            this.searchable = List.of();
            this.byConcept = Map.of();
            return;
        }

        JsonNode index = read(resourceLoader.getResource(properties.index()));

        List<BdcVariable> variables = variables(index.path("variables"));
        this.searchable = variables.stream()
                .sorted(Comparator.comparingInt(BdcVariable::studyCount).reversed()
                        .thenComparing(BdcVariable::name))
                .map(variable -> new Indexed(variable, searchTokens(variable)))
                .toList();
        this.byConcept = concepts(index.path("concepts"));

        log.info("Loaded harmonized index generated {}: {} variables, {} concepts",
                index.path("generated").asString("unknown"), searchable.size(), byConcept.size());
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
        if (index == null || !index.path("variables").isArray() || !index.path("concepts").isArray()) {
            throw new IllegalStateException("The harmonized index at " + resource.getDescription()
                    + " has no variables and concepts arrays; regenerate it with "
                    + "tools/build_harmonized_index.py");
        }
        return index;
    }

    private static List<BdcVariable> variables(JsonNode rows) {
        List<BdcVariable> variables = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            String name = required(row, "name");
            variables.add(new BdcVariable(
                    name,
                    // Never null, so the frontend never has to choose between two name fields.
                    optional(row, "label") != null ? optional(row, "label") : name,
                    optional(row, "domain"),
                    strings(row.path("studies")),
                    strings(row.path("concepts"))));
        }
        return List.copyOf(variables);
    }

    private static Map<String, BdcCoverage> concepts(JsonNode rows) {
        Map<String, BdcCoverage> coverage = new HashMap<>(rows.size());
        for (JsonNode row : rows) {
            List<String> studies = strings(row.path("studies"));
            coverage.put(conceptKey(required(row, "id")),
                    new BdcCoverage(studies.size(), studies, strings(row.path("variableNames"))));
        }
        return Collections.unmodifiableMap(coverage);
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
