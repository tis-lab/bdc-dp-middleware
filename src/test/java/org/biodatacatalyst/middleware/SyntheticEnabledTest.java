package org.biodatacatalyst.middleware;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("synthetic")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="app.data-location=classpath:fixtures/data/")
class SyntheticEnabledTest {
    @Autowired TestRestTemplate http;
    @Test void loadsSchemaAndAllSyntheticOperations() {
        String query = """
            { studies { studyId participantCount }
              study(studyId:"study_one") { recordCounts { entity count } }
              participants(studyId:"study_one") { records }
              entityRecords(studyId:"study_one",entity:persons) { count }
              participant(studyId:"study_one",participantId:"p1")
              participantDetail(studyId:"study_one",participantId:"p1") { person conditions visits drugExposures demography measurementSets measurements { observation } counts { measurements } }
              participantRecords(studyId:"study_one",participantId:"p1",entity:conditions,concept:"HP:0000822",status:"PRESENT",relationship:"ONESELF") { count }
              measurements(studyId:"study_one",participantId:"p1") { count observations { source visitId } }
              definitions { definitionId }
              definition(definitionId:"hypertension") { conditionCodes }
              definitionCounts(definitionId:"hypertension") { matchedParticipantCount byStudy { studyId } }
              definitionParticipants(definitionId:"hypertension") { matchedParticipantCount participants { participant } }
            }
            """;
        JsonNode result = http.postForObject("/graphql",Map.of("query",query),JsonNode.class);
        assertThat(result.has("errors")).as(result.toString()).isFalse();
        assertThat(result.at("/data/studies/0/participantCount").asInt()).isEqualTo(2);
        assertThat(result.at("/data/definitionCounts/matchedParticipantCount").asInt()).isEqualTo(1);
        assertThat(result.at("/data/participantDetail/person/cause_of_death").isNull()).isTrue();
    }
}
