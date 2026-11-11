package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.math.BigInteger;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class SummaryAgeTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private JsonNode fixture() throws Exception {
        try(var in=getClass().getResourceAsStream("/summary/elasticsearch-response.json")) {
            return json.readTree(in);
        }
    }
    private SummaryModels.SummaryResponse map(JsonNode body,int minimum) {
        return new SummaryResponseMapper(new SummaryProperties("test-age-release",minimum)).map(body);
    }
    private ObjectNode ages(JsonNode body) {
        return (ObjectNode)body.at("/aggregations/studies/buckets/0/ages");
    }
    @Test void recordedAgesAreNotTheStudyTotal() throws Exception {
        var result=map(fixture(),1);
        assertThat(result.studies().getFirst().participant_count()).isEqualTo(BigInteger.valueOf(5));
        var age=result.studies().getFirst().participants().getFirst();
        assertThat(age.summarized_slot()).isEqualTo("age_at_index");
        assertThat(age.participant_count()).isEqualTo(BigInteger.valueOf(4));
        assertThat(age.values()).hasSize(3);
        assertThat(age.values().getFirst().bin_low()).isEqualByComparingTo("45");
        assertThat(age.values().getFirst().bin_high()).isEqualByComparingTo("55");
        assertThat(age.values().getFirst().bin_unit()).isEqualTo("a");
        Files.createDirectories(Path.of("target/summary-contract"));
        Files.writeString(Path.of("target/summary-contract/age.json"),json.writeValueAsString(result));
    }
    @Test void appliesThresholdAndPreservesStableIds() throws Exception {
        var all=map(fixture(),1).studies().getFirst().participants().getFirst();
        var suppressed=map(fixture(),2).studies().getFirst().participants().getFirst();
        assertThat(suppressed.values()).hasSize(1);
        assertThat(suppressed.values().getFirst().id()).isEqualTo(all.values().get(1).id());
        assertThat(map(fixture(),5).studies().getFirst().participants()).isEmpty();
    }
    @Test void missingAgesDoNotProduceInventedCounts() throws Exception {
        var body=fixture(); ages(body).put("doc_count",0);
        for(var b:ages(body).at("/ranges/buckets")) ((ObjectNode)b).put("doc_count",0);
        assertThat(map(body,1).studies().getFirst().participants()).isEmpty();
    }
    @Test void outOfBandParticipantsRemainInRecordedCount() throws Exception {
        var body=fixture();
        ((ObjectNode)ages(body).at("/ranges/buckets/0")).put("doc_count",0);
        var result=map(body,1).studies().getFirst().participants().getFirst();
        assertThat(result.participant_count()).isEqualTo(BigInteger.valueOf(4));
        assertThat(result.values()).hasSize(2);
    }
    @Test void rejectsInvalidOrIncompleteAgeResults() throws Exception {
        for(String field:List.of("from","to","doc_count")) {
            var body=fixture(); ((ObjectNode)ages(body).at("/ranges/buckets/0")).put(field,999999);
            assertThatThrownBy(()->map(body,1)).isInstanceOf(RuntimeException.class);
        }
        var body=fixture(); ((ObjectNode)ages(body).path("ranges")).putArray("buckets");
        assertThatThrownBy(()->map(body,1)).hasMessageContaining("incomplete");
    }
    @Test void rejectsOverlappingPopulationCounts() throws Exception {
        var body=fixture();
        for(var b:ages(body).at("/ranges/buckets")) ((ObjectNode)b).put("doc_count",2);
        assertThatThrownBy(()->map(body,1)).hasMessageContaining("exceed");
    }
    @Test void ageAggregationInheritsTheSelectedPopulation() {
        var query=java.util.Map.<String,Object>of("term",java.util.Map.of("id","test-only-id"));
        var body=json.valueToTree(SummaryAggregations.request(query));
        assertThat(body.path("query")).isEqualTo(json.valueToTree(query));
        assertThat(body.at("/aggs/studies/aggs/ages/filter/exists/field").asString()).isEqualTo("age_at_index");
        assertThat(body.at("/aggs/studies/aggs/ages/aggs/ranges/range/keyed").asBoolean()).isFalse();
        assertThat(SummaryAgeBins.days(45)).isEqualTo(16437);
        assertThat(SummaryAgeBins.days(55)).isEqualTo(20089);
        assertThat(SummaryAgeBins.days(65)).isEqualTo(23742);
        assertThat(SummaryAgeBins.days(80)).isEqualTo(29220);
    }
}
