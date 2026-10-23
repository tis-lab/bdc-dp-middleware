package org.biodatacatalyst.middleware.summary.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import tools.jackson.databind.ObjectMapper;

import org.biodatacatalyst.middleware.summary.CohortQuery;
import org.biodatacatalyst.middleware.summary.SummaryModels.SummaryResponse;
import org.biodatacatalyst.middleware.summary.SummaryService;

@Controller
public class SummaryQueryController {

    private final SummaryService summaries;
    private final ObjectMapper mapper;

    public SummaryQueryController(SummaryService summaries, ObjectMapper mapper) {
        this.summaries = summaries;
        this.mapper = mapper;
    }

    @QueryMapping
    public SummaryResponse summary(@Argument CohortQuery query) {
        return summaries.summary(query);
    }

    @QueryMapping
    public Object summaryJson(@Argument CohortQuery query) {
        return mapper.convertValue(summaries.summary(query), Object.class);
    }
}
