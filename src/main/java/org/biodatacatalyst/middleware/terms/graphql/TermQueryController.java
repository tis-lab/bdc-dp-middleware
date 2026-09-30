package org.biodatacatalyst.middleware.terms.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import org.biodatacatalyst.middleware.terms.TermResults;
import org.biodatacatalyst.middleware.terms.TermService;

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
}
