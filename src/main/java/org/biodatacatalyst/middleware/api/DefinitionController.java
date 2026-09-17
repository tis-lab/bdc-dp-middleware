package org.biodatacatalyst.middleware.api;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.biodatacatalyst.middleware.service.DefinitionService;

@Tag(name = "Definitions", description = "Definitions are defined by an explicit list of condition codes.")
@RestController
@RequestMapping("/api/v1/definitions")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", description = "Unknown definition or study",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class DefinitionController {

    private static final String DEFINITION_EXAMPLE = "hypertension";

    private final DefinitionService definitions;

    public DefinitionController(DefinitionService definitions) {
        this.definitions = definitions;
    }

    @Operation(operationId = "listDefinitions", summary = "List definitions and their condition codes")
    @GetMapping
    public List<DefinitionService.Definition> definitions() {
        return definitions.definitions();
    }

    @Operation(operationId = "getDefinition", summary = "Get one definition")
    @GetMapping("/{definitionId}")
    public DefinitionService.Definition definition(
            @Parameter(example = DEFINITION_EXAMPLE,
                    schema = @Schema(allowableValues = {"hypertension", "diabetes"}))
            @PathVariable("definitionId") String definitionId) {
        return definitions.definition(definitionId);
    }

    @Operation(operationId = "getDefinitionCounts", summary = "Count matching participants per study",
            description = "A participant with several matching conditions is counted once. Studies with no "
                    + "matches are included with a count of zero.")
    @GetMapping("/{definitionId}/counts")
    public DefinitionService.DefinitionCounts counts(
            @Parameter(example = DEFINITION_EXAMPLE,
                    schema = @Schema(allowableValues = {"hypertension", "diabetes"}))
            @PathVariable("definitionId") String definitionId) {
        return definitions.counts(definitionId);
    }

    @Operation(operationId = "listDefinitionParticipants", summary = "List matching participants",
            description = "Returns each matching Participant record with the study it came from. Conditions are "
                    + "matched inside their own study only. No matches returns 200 with count zero.")
    @GetMapping("/{definitionId}/participants")
    public DefinitionService.DefinitionParticipants participants(
            @Parameter(example = DEFINITION_EXAMPLE,
                    schema = @Schema(allowableValues = {"hypertension", "diabetes"}))
            @PathVariable("definitionId") String definitionId,
            @Parameter(description = "Restrict to one study. Omit or leave blank to search every study.",
                    schema = @Schema(allowableValues = {"study_one", "study_two"}))
            @RequestParam(name = "studyId", required = false) String studyId) {
        return definitions.participants(definitionId, studyId);
    }
}
