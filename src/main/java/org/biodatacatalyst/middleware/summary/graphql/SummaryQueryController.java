package org.biodatacatalyst.middleware.summary.graphql;

import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import tools.jackson.databind.ObjectMapper;
import org.biodatacatalyst.middleware.summary.SummaryModels.*;
import org.biodatacatalyst.middleware.summary.SummaryService;

@Controller
public class SummaryQueryController {
    private final SummaryService summaries;
    private final ObjectMapper mapper;
    public SummaryQueryController(SummaryService summaries, ObjectMapper mapper) {
        this.summaries = summaries;
        this.mapper = mapper;
    }
    @QueryMapping public SummaryResponse summary(@Argument String sourceId, @Argument List<String> include, @Argument List<String> exclude) {
        return summaries.summary(sourceId, include, exclude);
    }
    @QueryMapping public Object summaryJson(@Argument String sourceId, @Argument List<String> include, @Argument List<String> exclude) {
        return mapper.convertValue(summaries.summary(sourceId, include, exclude), Object.class);
    }
    @QueryMapping public List<String> summarySources() { return summaries.sources(); }
    @QueryMapping public List<SummaryTerm> summaryConcepts(@Argument String sourceId) {
        return summaries.concepts(sourceId);
    }
}
