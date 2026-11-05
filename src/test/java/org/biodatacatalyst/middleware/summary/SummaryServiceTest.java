package org.biodatacatalyst.middleware.summary;

import java.math.BigInteger;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class SummaryServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private JsonNode fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/summary/elasticsearch-response.json")) { return json.readTree(in); }
    }
    @Test void pageLoadUsesMatchAllWithoutWildcardOrSeed() throws Exception {
        var result = new SummaryResponseMapper(new SummaryProperties("test-release",1)).map(fixture());
        assertThat(result.seed()).isNull();
        assertThat(result.filter()).isEmpty();
        var wire = json.valueToTree(result);
        assertThat(wire.has("filter_expression")).isFalse();
        assertThat(wire.has("seed")).isFalse();
        assertThat(result.studies().getFirst().participant_count()).isEqualTo(BigInteger.valueOf(5));
        var condition = result.studies().getFirst().conditions().getFirst();
        assertThat(condition.participant_count()).isEqualTo(BigInteger.valueOf(4));
        assertThat(condition.values().getFirst().participant_count()).isEqualTo(BigInteger.valueOf(3)); // five records, three participants
        assertThat(condition.record_count()).isNull();
        assertThat(result.studies().getFirst().demographics().get(1).participant_count()).isEqualTo(BigInteger.valueOf(4));
        Files.createDirectories(Path.of("target/summary-contract"));
        Files.writeString(Path.of("target/summary-contract/page-load.json"),json.writeValueAsString(result));
    }
    @Test void filteredResponsePreservesMandatoryIdsInExistingContract() throws Exception {
        var ids = SummaryFilterCompiler.compile(java.util.Map.of("summaryId",
                "urn:bdc:summary:ZGVtb2dyYXBoeXxzZXh8RkVNQUxF")).requiredIds();
        var result = new SummaryResponseMapper(new SummaryProperties("test-release",1)).map(fixture(),ids);
        assertThat(result.filter()).isEqualTo(ids);
        Files.createDirectories(Path.of("target/summary-contract"));
        Files.writeString(Path.of("target/summary-contract/filtered.json"),json.writeValueAsString(result));
    }
    @Test void suppressesSmallStudiesConceptsAndValues() throws Exception {
        var result = new SummaryResponseMapper(new SummaryProperties("test-release",3)).map(fixture());
        Files.createDirectories(Path.of("target/summary-contract"));
        Files.writeString(Path.of("target/summary-contract/suppressed.json"),json.writeValueAsString(result));
        assertThat(result.studies().getFirst().conditions().getFirst().values()).hasSize(1);
        assertThat(result.studies().getFirst().demographics().getFirst().values()).hasSize(1);
        assertThat(new SummaryResponseMapper(new SummaryProperties("test-release",6)).map(fixture()).studies()).isEmpty();
    }
    @Test void rejectsTruncationMissingStudyAndImpossibleCounts() throws Exception {
        for (String pointer : List.of("/aggregations/studies/sum_other_doc_count", "/aggregations/studies/doc_count_error_upper_bound", "/aggregations/missing_study/doc_count")) {
            var body = fixture(); int split=pointer.lastIndexOf('/');
            ((ObjectNode)body.at(pointer.substring(0,split))).put(pointer.substring(split+1),1);
            assertThatThrownBy(() -> new SummaryResponseMapper(new SummaryProperties("test-release",1)).map(body)).isInstanceOf(RuntimeException.class);
        }
        var body=fixture();
        ((ObjectNode)body.at("/aggregations/studies/buckets/0/conditions/concepts/buckets/0/participants")).put("doc_count",6);
        assertThatThrownBy(() -> new SummaryResponseMapper(new SummaryProperties("test-release",1)).map(body)).hasMessageContaining("exceeds");
    }
    @Test void rejectsStudyCountAboveSelectedPopulation() throws Exception {
        var body=fixture();
        ((ObjectNode)body.at("/aggregations/studies/buckets/0")).put("doc_count",999);
        assertThatThrownBy(() -> new SummaryResponseMapper(new SummaryProperties("test-release",1)).map(body))
                .hasMessageContaining("exceeds");
    }
    @Test void emptyPopulationProducesNoInventedStudy() throws Exception {
        var body=fixture();
        ((ObjectNode)body.at("/aggregations/studies")).putArray("buckets");
        ((ObjectNode)body.at("/hits/total")).put("value",0);
        var result=new SummaryResponseMapper(new SummaryProperties("test-release",1)).map(body);
        assertThat(result.studies()).isEmpty();
        assertThat(result.research_studies()).isEmpty();
        Files.createDirectories(Path.of("target/summary-contract"));
        Files.writeString(Path.of("target/summary-contract/empty.json"),json.writeValueAsString(result));
    }
    @Test void aggregationCountsParentsAndRetainsParticipantsWithoutConditions() {
        var request=json.valueToTree(SummaryAggregations.request());
        assertThat(request.at("/query/match_all").isObject()).isTrue();
        assertThat(request.at("/aggs/studies/terms/field").asString()).isEqualTo("member_of_research_study.id");
        assertThat(request.at("/aggs/studies/aggs/conditions/aggs/concepts/aggs/participants/reverse_nested").isObject()).isTrue();
        assertThat(request.at("/aggs/studies/aggs/demographics/aggs/race/aggs/participants/reverse_nested").isObject()).isTrue();
    }
    @Test void deploymentMetadataHasNoDefaults() {
        assertThatThrownBy(() -> new SummaryProperties(null,1)).hasMessageContaining("SUMMARY_RELEASE");
        assertThatThrownBy(() -> new SummaryProperties("release",null)).hasMessageContaining("SUMMARY_MIN_COUNT");
        assertThatThrownBy(() -> new SummaryProperties("release",0)).hasMessageContaining("SUMMARY_MIN_COUNT");
    }
}
