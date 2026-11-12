package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class SummaryFocusTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final SummaryResponseMapper mapper = new SummaryResponseMapper(new SummaryProperties("test-release", 1));

    private JsonNode focusedFixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/summary/elasticsearch-response.json")) {
            var body = json.readTree(input);
            for (var study : body.at("/aggregations/studies/buckets")) {
                var conditions = (ObjectNode) study.path("conditions");
                var selected = conditions.deepCopy();
                conditions.removeAll();
                conditions.set("selected", selected);
            }
            return body;
        }
    }

    @Test void focusChangesOnlyConditionAggregationNotCohortOrDemographics() {
        var filter = SummaryFilterCompiler.compile(Map.of("condition", Map.of("concept", "MONDO:0005044", "selection", "INCLUDE")));
        var baseline = json.valueToTree(SummaryAggregations.request(filter.query()));
        var focused = json.valueToTree(SummaryAggregations.request(filter.query(), List.of("UPHENO:7000207", "ATC:C03")));
        assertThat(focused.path("query")).isEqualTo(baseline.path("query"));
        for (String path : List.of("/aggs/studies/terms", "/aggs/missing_study",
                "/aggs/studies/aggs/demographics", "/aggs/studies/aggs/ages"))
            assertThat(focused.at(path)).isEqualTo(baseline.at(path));
        var selected = focused.at("/aggs/studies/aggs/conditions/aggs/selected");
        assertThat(selected.at("/filter/terms/condition.condition_concept")).isEqualTo(json.valueToTree(List.of("UPHENO:7000207", "ATC:C03")));
        assertThat(selected.at("/aggs/concepts/aggs/participants/reverse_nested").isObject()).isTrue();
        assertThat(selected.at("/aggs/concepts/terms/size").asInt()).isGreaterThanOrEqualTo(100);
    }

    @Test void rareFocusedConceptIsMappedWithoutChangingStudyCounts() throws Exception {
        var body = focusedFixture();
        var concept = (ObjectNode) body.at("/aggregations/studies/buckets/0/conditions/selected/concepts/buckets/0");
        concept.put("key", "UPHENO:7000207");
        var result = mapper.map(body, List.of(), List.of("UPHENO:7000207"));
        assertThat(result.focus()).containsExactly("UPHENO:7000207");
        assertThat(result.studies().getFirst().participant_count().intValue()).isEqualTo(5);
        assertThat(result.studies().getFirst().conditions().getFirst().condition_concept()).isEqualTo("UPHENO:7000207");
        assertThat(result.studies().getFirst().demographics()).isNotEmpty();
        assertThat(json.valueToTree(result).has("seed")).isFalse();
        write("focused.json", result);
    }

    @Test void absentFocusDoesNotRemoveParticipantsOrInventZeroCounts() throws Exception {
        var body = focusedFixture();
        for (var study : body.at("/aggregations/studies/buckets"))
            ((ObjectNode)study.at("/conditions/selected/concepts")).putArray("buckets");
        var result = mapper.map(body, List.of(), List.of("ATC:C03"));
        assertThat(result.studies().getFirst().participant_count().intValue()).isEqualTo(5);
        assertThat(result.studies().getFirst().conditions()).isEmpty();
        assertThat(result.terms()).extracting(SummaryModels.SummaryTerm::curie).contains("ATC:C03");
        write("focused-empty.json", result);
    }

    @Test void focusDoesNotBypassSuppressionOrCompletenessChecks() throws Exception {
        var body = focusedFixture();
        var concept = (ObjectNode) body.at("/aggregations/studies/buckets/0/conditions/selected/concepts/buckets/0");
        String curie = concept.path("key").asString();
        var suppressed = new SummaryResponseMapper(new SummaryProperties("test-release", 5)).map(body, List.of(), List.of(curie));
        assertThat(suppressed.studies().getFirst().conditions()).isEmpty();
        ((ObjectNode)body.at("/aggregations/studies/buckets/0/conditions/selected/concepts")).put("sum_other_doc_count", 1);
        assertThatThrownBy(() -> mapper.map(body, List.of(), List.of(curie))).hasMessageContaining("incomplete");
    }

    @Test void validatesBoundsAndKeepsExactConceptValues() {
        assertThat(SummaryFocus.validate(null)).isEmpty();
        assertThat(SummaryFocus.validate(List.of())).isEmpty();
        assertThat(SummaryFocus.validate(List.of("ATC:C03", "UPHENO:7000207", "ATC:C03")))
                .containsExactly("ATC:C03", "UPHENO:7000207");
        for (String invalid : List.of("", "HP:*", "HP:0000822 ", "ATC:C03|x", "ATC:C03\n", "missing-prefix", "X:" + "a".repeat(511)))
            assertThatThrownBy(() -> SummaryFocus.validate(List.of(invalid))).hasMessageContaining("focus");
        assertThatThrownBy(() -> SummaryFocus.validate(Collections.singletonList(null))).hasMessageContaining("focus");
        assertThatThrownBy(() -> SummaryFocus.validate(Collections.nCopies(101, "ATC:C03"))).hasMessageContaining("100");
        assertThat(SummaryAggregations.request(Map.of("match_all", Map.of()), List.of())).isEqualTo(SummaryAggregations.request());
    }

    private void write(String name, Object value) throws Exception {
        Files.createDirectories(Path.of("target/summary-contract"));
        Files.writeString(Path.of("target/summary-contract", name), json.writeValueAsString(value));
    }
}
