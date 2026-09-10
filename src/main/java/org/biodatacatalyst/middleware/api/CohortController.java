package org.biodatacatalyst.middleware.api;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import org.springframework.http.ProblemDetail;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.biodatacatalyst.middleware.service.CohortService;
import org.springframework.web.bind.annotation.*;

@OpenAPIDefinition(info = @Info(title = "BDC Data Portal Middleware", version = "0.1.0",
        description = "Middleware API for BDC Data Portal,"),
        servers = @Server(url = "/"))
@Tag(name = "Cohorts")
@RestController
@RequestMapping("/api/v1/cohorts")
public class CohortController {
    private final CohortService cohorts;

    public CohortController(CohortService cohorts) {
        this.cohorts = cohorts;
    }

    @Operation(operationId = "listCohorts", summary = "List cohort definitions")
    @GetMapping
    public List<CohortService.Cohort> cohorts() {
        return cohorts.cohorts();
    }

    @Operation(operationId = "getCohort", summary = "Get a cohort definition", description = "Use hypertension or diabetes. Unknown cohort IDs return 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cohort definition", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "Unknown cohort ID",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{cohortId}")
    public CohortService.Cohort cohort(@Parameter(example = "hypertension", schema = @Schema(allowableValues = {"hypertension", "diabetes"}))
                                       @PathVariable("cohortId") String cohortId) {
        return cohorts.cohort(cohortId);
    }

    @Operation(operationId = "getCohortParticipants", summary = "Get all matching participants",
            description = "Returns matching Participant records, each with its study identifier. Conditions must match a cohort code, PRESENT status and ONESELF relationship. No matches returns 200 with count zero and an empty list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching participants, possibly an empty list", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "Unknown cohort or study ID",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Missing, unreadable, invalid or ambiguous YAML input",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{cohortId}/participants")
    public CohortService.CohortResponse participants(
            @Parameter(example = "hypertension", schema = @Schema(allowableValues = {"hypertension", "diabetes"}))
            @PathVariable("cohortId") String cohortId,
            @Parameter(description = "Optional: restrict to one study; omit or leave blank for both studies", schema = @Schema(allowableValues = {"study_one", "study_two"}))
            @RequestParam(name = "studyId", required = false) String studyId) {
        return cohorts.participants(cohortId, studyId);
    }
}
