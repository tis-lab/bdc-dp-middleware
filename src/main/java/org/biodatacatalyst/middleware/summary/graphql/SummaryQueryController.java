package org.biodatacatalyst.middleware.summary.graphql;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.Argument;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.biodatacatalyst.middleware.summary.SummaryModels.SummaryResponse;
import org.biodatacatalyst.middleware.summary.SummaryService;

@Controller
public class SummaryQueryController {
    private final SummaryService summaries;
    public SummaryQueryController(SummaryService summaries) {
        this.summaries = summaries;
    }
    @QueryMapping public SummaryResponse summary(@Argument("filter") Map<String,Object> filter) {
        return summaries.summary(filter);
    }
}
