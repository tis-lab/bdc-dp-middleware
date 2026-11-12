package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.graphql;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.Argument;
import java.util.Map;
import java.util.List;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryModels.SummaryResponse;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryService;

@Controller
public class SummaryQueryController {
    private final SummaryService summaries;
    public SummaryQueryController(SummaryService summaries) {
        this.summaries = summaries;
    }
    @QueryMapping public SummaryResponse summary(@Argument("filter") Map<String,Object> filter,
                                                @Argument("focus") List<String> focus) {
        return summaries.summary(filter, focus);
    }

    /** Transport-only compatibility; seed is not serialized in the LinkML response model. */
    @SchemaMapping(typeName = "SummaryResponse", field = "seed")
    public String seed(SummaryResponse response) { return null; }
}
