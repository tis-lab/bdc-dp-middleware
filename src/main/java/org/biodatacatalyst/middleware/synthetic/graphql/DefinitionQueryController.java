package org.biodatacatalyst.middleware.synthetic.graphql;

import org.springframework.context.annotation.Profile;

import java.util.List;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import org.biodatacatalyst.middleware.synthetic.service.DefinitionService;

/** Definition queries. Rules are explicit code lists. */
@Profile("synthetic")
@Controller
public class DefinitionQueryController {

    private final DefinitionService definitions;

    public DefinitionQueryController(DefinitionService definitions) {
        this.definitions = definitions;
    }

    @QueryMapping
    public List<DefinitionService.Definition> definitions() {
        return definitions.definitions();
    }

    @QueryMapping
    public DefinitionService.Definition definition(@Argument String definitionId) {
        return definitions.definition(definitionId);
    }

    @QueryMapping
    public DefinitionService.DefinitionCounts definitionCounts(@Argument String definitionId) {
        return definitions.counts(definitionId);
    }

    @QueryMapping
    public DefinitionService.DefinitionParticipants definitionParticipants(@Argument String definitionId,
                                                               @Argument String studyId) {
        return definitions.participants(definitionId, studyId);
    }
}
