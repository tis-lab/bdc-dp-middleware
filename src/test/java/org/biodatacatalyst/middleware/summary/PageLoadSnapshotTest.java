package org.biodatacatalyst.middleware.summary;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import java.math.BigInteger;

class PageLoadSnapshotTest {
    final JsonMapper json=JsonMapper.builder().build();
    JsonNode fixture() throws Exception {
        // Two nested rows can belong to one participant; use reverse_nested counts, never row counts.
        String facet="\"participants\":{\"doc_count\":2},\"values\":{\"sum_other_doc_count\":0,\"doc_count_error_upper_bound\":0,\"buckets\":[{\"key\":\"EXAMPLE\",\"doc_count\":7,\"participants\":{\"doc_count\":2}}]}";
        return json.readTree("{\"hits\":{\"total\":{\"value\":3,\"relation\":\"eq\"}},\"aggregations\":{\"demographics\":{\"sex\":{"+facet+"},\"race\":{"+facet+"},\"ethnicity\":{"+facet+"}}}}");
    }
    @Test void countsRootParticipantsAndDistinctParents() throws Exception {
        var result=PageLoadSnapshotService.map(fixture(),new SummaryProperties("test-only",1));
        assertThat(result.participantCount().value()).isEqualTo(BigInteger.valueOf(3));
        assertThat(result.demographics().getFirst().values().getFirst().participantCount().value()).isEqualTo(BigInteger.TWO);
        assertThat(result.demographics().getFirst().missingParticipantCount().value()).isEqualTo(BigInteger.ONE);
    }
    @Test void suppressesSmallPositiveCounts() throws Exception {
        var result=PageLoadSnapshotService.map(fixture(),new SummaryProperties("test-only",3));
        assertThat(result.participantCount().suppressed()).isFalse();
        assertThat(result.demographics().getFirst().values().getFirst().participantCount().value()).isNull();
        assertThat(result.demographics().getFirst().missingParticipantCount().suppressed()).isTrue();
    }
    @Test void rejectsIncompleteBuckets() throws Exception {
        var body=fixture();
        ((ObjectNode)body.at("/aggregations/demographics/race/values")).put("sum_other_doc_count",1);
        assertThatThrownBy(()->PageLoadSnapshotService.map(body,new SummaryProperties("test-only",1))).hasMessageContaining("Incomplete");
    }
    @Test void rejectsImpossibleCounts() throws Exception {
        var body=fixture();
        ((ObjectNode)body.at("/aggregations/demographics/sex/participants")).put("doc_count",4);
        assertThatThrownBy(()->PageLoadSnapshotService.map(body,new SummaryProperties("test-only",1))).hasMessageContaining("invalid");
    }
    @Test void zeroPopulationIsNotSuppressed() throws Exception {
        var body=fixture();
        ((ObjectNode)body.at("/hits/total")).put("value",0);
        for (String slot : java.util.List.of("sex","race","ethnicity")) {
            ((ObjectNode)body.at("/aggregations/demographics/"+slot+"/participants")).put("doc_count",0);
            ((ObjectNode)body.at("/aggregations/demographics/"+slot+"/values")).putArray("buckets");
        }
        var result=PageLoadSnapshotService.map(body,new SummaryProperties("test-only",3));
        assertThat(result.participantCount().value()).isEqualTo(BigInteger.ZERO);
        assertThat(result.participantCount().suppressed()).isFalse();
        assertThat(result.demographics().getFirst().values()).isEmpty();
    }
    @Test void retainsOverlappingCategoriesWithoutSumming() throws Exception {
        var body=fixture();
        var buckets=(tools.jackson.databind.node.ArrayNode)body.at("/aggregations/demographics/race/values/buckets");
        var other=(ObjectNode)buckets.get(0).deepCopy(); other.put("key","SECOND"); buckets.add(other);
        var result=PageLoadSnapshotService.map(body,new SummaryProperties("test-only",1));
        assertThat(result.demographics().get(1).values()).hasSize(2);
        assertThat(result.demographics().get(1).recordedParticipantCount().value()).isEqualTo(BigInteger.TWO);
        assertThat(result.participantCount().value()).isEqualTo(BigInteger.valueOf(3));
    }
    @Test void requiresExplicitIndexConfirmation() {
        assertThatThrownBy(()->new PageLoadSnapshotService(null,new SummaryProperties("test-only",1),false).pageLoadSnapshot())
            .hasMessageContaining("confirmation");
    }
    @Test void requestUsesGlobalNestedCountsAndNoStudyGrouping() {
        var body=json.valueToTree(PageLoadSnapshotService.request());
        assertThat(body.path("query").has("match_all")).isTrue();
        assertThat(body.at("/aggs/studies").isMissingNode()).isTrue();
        assertThat(body.at("/aggs/demographics/aggs/race/aggs/values/aggs/participants").has("reverse_nested")).isTrue();
    }
}
