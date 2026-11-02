package org.biodatacatalyst.middleware.summary;

import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.biodatacatalyst.middleware.summary.SummaryModels.SummaryResponse;

@Component
public class ExampleSummarySource implements SummarySource {
    private final Map<String, SummaryResponse> sources;
    public ExampleSummarySource(ResourceLoader resources) {
        var reader = new SummaryReader();
        Map<String, SummaryResponse> loaded = new LinkedHashMap<>();
        for (String id : List.of("t2d", "t2d-female")) {
            try (var input = resources.getResource("classpath:summary/" + id + ".yaml").getInputStream()) {
                loaded.put(id, reader.read(input));
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException("Cannot load summary source " + id, failure);
            }
        }
        sources = Collections.unmodifiableMap(loaded);
    }
    @Override public Optional<SummaryResponse> find(String sourceId) { return Optional.ofNullable(sources.get(sourceId)); }
    @Override public List<String> sourceIds() { return List.copyOf(sources.keySet()); }
}
