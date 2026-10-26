package org.biodatacatalyst.middleware.search;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** Developer diagnostic. No summary-schema mapping or raw backend response is exposed. */
@Controller
public class SearchProbeController {
    private final MetadataSearch search;

    public SearchProbeController(MetadataSearch search) {
        this.search = search;
    }

    @QueryMapping
    public SearchProbeResult searchProbe() {
        var execution = search.executeProbe();
        return new SearchProbeResult("SUCCEEDED", "elasticsearch", execution.httpStatus(),
                (int) Math.min(execution.elapsedMillis(), Integer.MAX_VALUE));
    }

    public record SearchProbeResult(String status, String backend, int httpStatus, int elapsedMillis) {}
}
