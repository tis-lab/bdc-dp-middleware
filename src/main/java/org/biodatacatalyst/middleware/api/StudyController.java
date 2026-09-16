package org.biodatacatalyst.middleware.api;

import java.util.List;
import java.util.Map;

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

import org.biodatacatalyst.middleware.model.ApiModels.MeasurementList;
import org.biodatacatalyst.middleware.model.ApiModels.ParticipantDetail;
import org.biodatacatalyst.middleware.model.ApiModels.RecordList;
import org.biodatacatalyst.middleware.model.ApiModels.StudyDetail;
import org.biodatacatalyst.middleware.model.ApiModels.StudySummary;
import org.biodatacatalyst.middleware.service.StudyService;

@Tag(name = "Studies", description = "Study selection, participant lists and details.")
@RestController
@RequestMapping("/api/v1/studies")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "400", description = "A filter that does not apply to the requested entity",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Unknown study, participant or entity",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class StudyController {

    private static final String ENTITY_DESCRIPTION = "One of the eight record types.";

    private final StudyService studies;

    public StudyController(StudyService studies) {
        this.studies = studies;
    }

    @Operation(operationId = "listStudies", summary = "List studies for the study selector")
    @GetMapping
    public List<StudySummary> studies() {
        return studies.studies();
    }

    @Operation(operationId = "getStudy", summary = "Get one study with its record counts",
            description = "recordCounts holds top-level records per file. embeddedObservationCount holds the "
                    + "observations nested inside measurement sets; they are not top-level records, so do not "
                    + "add the two numbers together.")
    @GetMapping("/{studyId}")
    public StudyDetail study(@Parameter(example = "study_one") @PathVariable("studyId") String studyId) {
        return studies.study(studyId);
    }

    @Operation(operationId = "listParticipants", summary = "List all participants in a study")
    @GetMapping("/{studyId}/participants")
    public RecordList participants(@Parameter(example = "study_one") @PathVariable("studyId") String studyId) {
        return studies.records(studyId, "participants");
    }

    @Operation(operationId = "listEntityRecords", summary = "Browse the original records of one file",
            description = "Unjoined and unfiltered. measurements holds standalone MeasurementObservation "
                    + "records only; the observations nested in measurement-sets are returned with those sets.")
    @GetMapping("/{studyId}/entities/{entity}")
    public RecordList entityRecords(
            @Parameter(example = "study_one") @PathVariable("studyId") String studyId,
            @Parameter(description = ENTITY_DESCRIPTION, schema = @Schema(allowableValues = {
                    "persons", "participants", "demography", "conditions", "visits", "drug-exposures",
                    "measurements", "measurement-sets"}))
            @PathVariable("entity") String entity) {
        return studies.records(studyId, entity);
    }

    @Operation(operationId = "getParticipant", summary = "Get the original Participant record")
    @GetMapping("/{studyId}/participants/{participantId}")
    public Map<String, Object> participant(
            @Parameter(example = "study_one") @PathVariable("studyId") String studyId,
            @PathVariable("participantId") String participantId) {
        return studies.participant(studyId, participantId);
    }

    @Operation(operationId = "getParticipantDetail", summary = "Get one participant joined with all record types",
            description = "person is null when the participant has no associated_person reference. measurements "
                    + "holds standalone and nested observations with their provenance; measurementSets repeats "
                    + "those nested observations in their original groups, so use counts rather than adding the "
                    + "two lists.")
    @GetMapping("/{studyId}/participants/{participantId}/detail")
    public ParticipantDetail detail(
            @Parameter(example = "study_one") @PathVariable("studyId") String studyId,
            @PathVariable("participantId") String participantId) {
        return studies.details(studyId, participantId);
    }

    @Operation(operationId = "listParticipantRecords", summary = "Get a participant's records of one type",
            description = """
                    Records linked by associated_participant, with the original fields and null values.
                    persons is resolved through the participant's associated_person reference and returns zero \
                    or one record.
                    Filters are exact and case sensitive; blank values are ignored. concept applies to \
                    conditions, drug-exposures and measurements; status applies to conditions and \
                    drug-exposures; relationship applies to conditions. A filter sent to any other entity \
                    returns 400.
                    measurements here returns standalone records only. Use the measurements route for the \
                    combined view.""")
    @GetMapping("/{studyId}/participants/{participantId}/records/{entity}")
    public RecordList participantRecords(
            @Parameter(example = "study_one") @PathVariable("studyId") String studyId,
            @PathVariable("participantId") String participantId,
            @Parameter(description = ENTITY_DESCRIPTION, schema = @Schema(allowableValues = {
                    "persons", "demography", "conditions", "visits", "drug-exposures",
                    "measurements", "measurement-sets"}))
            @PathVariable("entity") String entity,
            @Parameter(example = "MONDO:0005044") @RequestParam(name = "concept", required = false) String concept,
            @Parameter(example = "PRESENT") @RequestParam(name = "status", required = false) String status,
            @Parameter(example = "ONESELF") @RequestParam(name = "relationship", required = false)
            String relationship) {
        return studies.relatedRecords(studyId, participantId, entity, concept, status, relationship);
    }

    @Operation(operationId = "listParticipantMeasurements",
            summary = "Get standalone and nested observations together",
            description = "Each observation reports its source, the parent measurementSetId when it came from a "
                    + "set, and an effective visitId inherited from the parent set when the observation has none. "
                    + "The two sources are distinct records, so the total does not double count. No sorting, "
                    + "rounding or unit conversion is applied.")
    @GetMapping("/{studyId}/participants/{participantId}/measurements")
    public MeasurementList measurements(
            @Parameter(example = "study_one") @PathVariable("studyId") String studyId,
            @PathVariable("participantId") String participantId,
            @Parameter(description = "Exact observation_type", example = "OMOP:4152194")
            @RequestParam(name = "concept", required = false) String concept,
            @Parameter(description = "Exact effective visit identifier")
            @RequestParam(name = "visitId", required = false) String visitId) {
        return studies.measurements(studyId, participantId, concept, visitId);
    }
}
