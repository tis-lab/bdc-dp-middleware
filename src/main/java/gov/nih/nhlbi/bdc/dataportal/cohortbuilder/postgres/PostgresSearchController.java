package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.postgres;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryModels.SummaryResponse;

@Controller
public class PostgresSearchController {
    private final PostgresSearchService service;
    public PostgresSearchController(PostgresSearchService service) { this.service=service; }
    @QueryMapping
    public SummaryResponse search(@Argument("filter") Map<String,Object> filter, @Argument("focus") List<String> focus) {
        return service.search(filter,focus);
    }
}
