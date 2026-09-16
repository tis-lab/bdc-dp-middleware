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

import org.biodatacatalyst.middleware.service.CohortService;

@Tag(name = "Cohorts", description = "Cohorts are defined by an explicit list of condition codes.")
@RestController
@RequestMapping("/api/v1/cohorts")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", description = "Unknown cohort or study",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class CohortController {

    private static final String COHORT_EXAMPLE = "hypertension";

    private final CohortService cohorts;

    public CohortController(CohortService cohorts) {
        this.cohorts = cohorts;
    }

    @Operation(operationId = "listCohorts", summary = "List cohort definitions and their condition codes")
    @GetMapping
    public List<CohortService.CohortDefinition> cohorts() {
        return cohorts.cohorts();
    }

    @Operation(operationId = "getCohort", summary = "Get one cohort definition")
    @GetMapping("/{cohortId}")
    public CohortService.CohortDefinition cohort(
            @Parameter(example = COHORT_EXAMPLE,
                    schema = @Schema(allowableValues = {"hypertension", "diabetes"}))
            @PathVariable("cohortId") String cohortId) {
        return cohorts.cohort(cohortId);
    }

    @Operation(operationId = "getCohortCounts", summary = "Count matching participants per study",
            description = "A participant with several matching conditions is counted once. Studies with no "
                    + "matches are included with a count of zero.")
    @GetMapping("/{cohortId}/counts")
    public CohortService.CohortCounts counts(
            @Parameter(example = COHORT_EXAMPLE,
                    schema = @Schema(allowableValues = {"hypertension", "diabetes"}))
            @PathVariable("cohortId") String cohortId) {
        return cohorts.counts(cohortId);
    }

    @Operation(operationId = "listCohortParticipants", summary = "List matching participants",
            description = "Returns each matching Participant record with the study it came from. Conditions are "
                    + "matched inside their own study only. No matches returns 200 with count zero.")
    @GetMapping("/{cohortId}/participants")
    public CohortService.CohortParticipants participants(
            @Parameter(example = COHORT_EXAMPLE,
                    schema = @Schema(allowableValues = {"hypertension", "diabetes"}))
            @PathVariable("cohortId") String cohortId,
            @Parameter(description = "Restrict to one study. Omit or leave blank to search every study.",
                    schema = @Schema(allowableValues = {"study_one", "study_two"}))
            @RequestParam(name = "studyId", required = false) String studyId) {
        return cohorts.participants(cohortId, studyId);
    }
}
