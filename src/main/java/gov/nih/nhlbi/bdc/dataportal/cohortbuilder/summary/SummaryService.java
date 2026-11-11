package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.search.ElasticsearchTransport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryModels.SummaryResponse;

@Service
@EnableConfigurationProperties(SummaryProperties.class)
public class SummaryService {
    private final ElasticsearchTransport transport;
    private final SummaryResponseMapper mapper;
    public SummaryService(ElasticsearchTransport transport, SummaryProperties properties) {
        this.transport = transport;
        this.mapper = new SummaryResponseMapper(properties);
    }
    public SummaryResponse summary() {
        return summary(null);
    }
    public SummaryResponse summary(java.util.Map<String,Object> filter) {
        var selection = SummaryFilterCompiler.compile(filter);
        return mapper.map(transport.execute(SummaryAggregations.request(selection.query())).body(), selection.requiredIds());
    }
}
