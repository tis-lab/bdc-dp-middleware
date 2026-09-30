package org.biodatacatalyst.middleware.synthetic.graphql;

import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Map;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import org.biodatacatalyst.middleware.synthetic.model.ApiModels.MeasurementList;
import org.biodatacatalyst.middleware.synthetic.model.ApiModels.ParticipantDetail;
import org.biodatacatalyst.middleware.synthetic.model.ApiModels.RecordList;
import org.biodatacatalyst.middleware.synthetic.model.ApiModels.StudyDetail;
import org.biodatacatalyst.middleware.synthetic.model.ApiModels.StudySummary;
import org.biodatacatalyst.middleware.synthetic.service.StudyService;

/** Study and participant queries, served from the YAML held in memory. */
@Profile("synthetic")
@Controller
public class StudyQueryController {

    private final StudyService studies;

    public StudyQueryController(StudyService studies) {
        this.studies = studies;
    }


    @QueryMapping
    public List<StudySummary> studies() {
        return studies.studies();
    }

    @QueryMapping
    public StudyDetail study(@Argument String studyId) {
        return studies.study(studyId);
    }

    @QueryMapping
    public RecordList participants(@Argument String studyId) {
        return studies.records(studyId, "participants");
    }

    @QueryMapping
    public RecordList entityRecords(@Argument String studyId, @Argument Entity entity) {
        return studies.records(studyId, entity.key());
    }

    @QueryMapping
    public Map<String, Object> participant(@Argument String studyId, @Argument String participantId) {
        return studies.participant(studyId, participantId);
    }

    @QueryMapping
    public ParticipantDetail participantDetail(@Argument String studyId, @Argument String participantId) {
        return studies.details(studyId, participantId);
    }

    @QueryMapping
    public RecordList participantRecords(@Argument String studyId,
                                         @Argument String participantId,
                                         @Argument Entity entity,
                                         @Argument String concept,
                                         @Argument String status,
                                         @Argument String relationship) {
        return studies.relatedRecords(studyId, participantId, entity.key(), concept, status, relationship);
    }

    @QueryMapping
    public MeasurementList measurements(@Argument String studyId,
                                        @Argument String participantId,
                                        @Argument String concept,
                                        @Argument String visitId) {
        return studies.measurements(studyId, participantId, concept, visitId);
    }

    /**
     * Record counts are a map keyed by entity, and two of those keys contain hyphens, which GraphQL
     * field names cannot. They are exposed as a list of pairs instead.
     */
    @SchemaMapping(typeName = "StudyDetail", field = "recordCounts")
    public List<RecordCount> recordCounts(StudyDetail study) {
        return study.recordCounts().entrySet().stream()
                .map(entry -> new RecordCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    public record RecordCount(String entity, int count) {
    }

}
