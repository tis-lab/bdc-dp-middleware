package org.biodatacatalyst.middleware.summary;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

import org.biodatacatalyst.middleware.summary.SummaryModels.SummaryResponse;

@Component
public class ExampleSummarySource implements SummarySource {

    private static final Logger log = LoggerFactory.getLogger(ExampleSummarySource.class);

    private static final List<String> DOCUMENTS = List.of(
            "classpath:summary/t2d.yaml",
            "classpath:summary/t2d-female.yaml");

    private final Map<Key, SummaryResponse> examples = new LinkedHashMap<>();

    public ExampleSummarySource(ResourceLoader resourceLoader) {
        YAMLMapper mapper = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        for (String location : DOCUMENTS) {
            Resource resource = resourceLoader.getResource(location);
            SummaryResponse response;
            try (InputStream input = resource.getInputStream()) {
                response = mapper.readValue(input, SummaryResponse.class);
            } catch (IOException | JacksonException failure) {
                // These ship inside the jar, so an unreadable one is a broken build.
                throw new IllegalStateException(
                        "Unable to read the summary example at " + resource.getDescription(), failure);
            }
            examples.put(Key.of(response.seed(), response.filter()), response);
        }

        log.info("Loaded {} summary example(s): {}", examples.size(), available());
    }

    @Override
    public Optional<SummaryResponse> find(List<String> include) {
        Set<String> included = Set.copyOf(include);
        return examples.entrySet().stream()
                .filter(entry -> included.contains(entry.getKey().seed()))
                .max(Comparator.comparingLong(entry -> entry.getKey().filter().stream()
                        .filter(included::contains)
                        .count()))
                .map(Map.Entry::getValue);
    }

    @Override
    public List<String> available() {
        return examples.keySet().stream().map(Key::describe).toList();
    }

    private record Key(String seed, Set<String> filter) {

        static Key of(String seed, List<String> filter) {
            return new Key(seed, filter == null ? Set.of() : Set.copyOf(filter));
        }

        String describe() {
            return filter.isEmpty() ? seed : seed + " (narrowed by " + String.join(", ", filter) + ")";
        }
    }
}
