package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.terms.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.terms.AutocompleteResults;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.terms.TermResults;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.terms.TermService;

@Controller
public class TermQueryController {

    private final TermService terms;

    public TermQueryController(TermService terms) {
        this.terms = terms;
    }

    @QueryMapping
    public TermResults terms(@Argument String query, @Argument int limit, @Argument int offset) {
        return terms.terms(query, limit, offset);
    }

    @QueryMapping
    public AutocompleteResults autocomplete(@Argument String query) {
        return terms.autocomplete(query);
    }
}
