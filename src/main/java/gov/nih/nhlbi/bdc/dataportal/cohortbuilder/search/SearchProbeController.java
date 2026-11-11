package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.search;

import java.util.List;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.search.SearchResponseMapper.SearchProbeResult;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.search.SearchResponseMapper.SearchCountResult;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.BAD_USER_INPUT;

/** Live snapshot operations; per-study summary-schema mapping is a separate contract. */
@Controller
public class SearchProbeController {
    private final MetadataSearch search;

    public SearchProbeController(MetadataSearch search) {
        this.search = search;
    }

    @QueryMapping
    public SearchProbeResult searchProbe() {
        return SearchResponseMapper.probe(search.executeProbe());
    }

    @QueryMapping
    public SearchCountResult searchConditions(@Argument("keyword") String keyword,
                                              @Argument("keywords") List<String> keywords) {
        if ((keyword == null) == (keywords == null)) {
            throw new ApiException(BAD_USER_INPUT,
                    "Supply either keyword or keywords, not both.");
        }
        var selection = new ConditionSelection(keyword == null ? keywords : List.of(keyword));
        var execution = search.executeConditions(selection.keywords());
        return SearchResponseMapper.conditions(keyword, selection.keywords(), execution);
    }
}
