package org.biodatacatalyst.middleware.terms;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.biodatacatalyst.middleware.harmonized.BdcCoverage;
import org.biodatacatalyst.middleware.harmonized.HarmonizedVariableLibrary;
import org.biodatacatalyst.middleware.harmonized.HarmonizedVariableProperties;
import org.biodatacatalyst.middleware.monarch.ConceptPage;
import org.biodatacatalyst.middleware.monarch.MonarchClient;
import org.biodatacatalyst.middleware.monarch.SuggestionPage;
import org.biodatacatalyst.middleware.shared.SearchItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class TermServiceMergeTest {

    private static final int VARIABLES = 3;

    private static Term concept(int n) {
        return new Term("MONDO:" + n, "concept " + n, "biolink:Disease", null, List.of(),
                "concept " + n + " (MONDO:" + n + ")", BdcCoverage.none());
    }

    private static ConceptPage page(int n, int total) {
        return new ConceptPage(
                IntStream.range(0, n).mapToObj(TermServiceMergeTest::concept).toList(), total, n);
    }

    private static TermService service(MonarchClient monarch) {
        return new TermService(monarch, HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true,
                        "classpath*:harmonized/*-ingest/*.yaml",
                        "classpath:harmonized/harmonized_vars.tsv", 25)));
    }

    private static MonarchClient searching(ConceptPage page) {
        MonarchClient monarch = mock(MonarchClient.class);
        when(monarch.search(anyString(), anyInt(), anyInt())).thenReturn(page);
        return monarch;
    }

    private static List<String> types(List<SearchItem> items) {
        return items.stream().map(i -> i.getClass().getSimpleName()).toList();
    }

    @Test
    void fillsTheFirstPageWithVariablesThenConcepts() {
        TermResults results = service(searching(page(17, 400))).terms("hyper", 20, 0);

        assertThat(results.items()).hasSize(20);
        assertThat(types(results.items()).subList(0, VARIABLES))
                .containsOnly("BdcVariable");
        assertThat(types(results.items()).subList(VARIABLES, 20)).containsOnly("Term");
        assertThat(results.bdcVariableCount()).isEqualTo(VARIABLES);
        assertThat(results.total()).isEqualTo(403);
        assertThat(results.hasMore()).isTrue();
    }

    @Test
    void asksUpstreamOnlyForTheSlotsVariablesDidNotTake() {
        MonarchClient monarch = searching(page(17, 400));

        service(monarch).terms("hyper", 20, 0);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        verify(monarch).search(anyString(), limit.capture(), offset.capture());
        assertThat(limit.getValue()).isEqualTo(17);
        assertThat(offset.getValue()).isZero();
    }

    @Test
    void shiftsTheUpstreamOffsetBackByTheVariablesAlreadyShown() {
        MonarchClient monarch = searching(page(20, 400));

        TermResults results = service(monarch).terms("hyper", 20, 20);

        verify(monarch).search("hyper", 20, 17);
        assertThat(types(results.items())).containsOnly("Term");
        assertThat(results.bdcVariableCount()).isEqualTo(VARIABLES);
        assertThat(results.total()).isEqualTo(403);
    }

    @Test
    void handlesAPageStartingPartwayThroughTheVariables() {
        MonarchClient monarch = searching(page(18, 400));

        TermResults results = service(monarch).terms("hyper", 20, 1);

        verify(monarch).search("hyper", 18, 0);
        assertThat(types(results.items()).subList(0, 2)).containsOnly("BdcVariable");
        assertThat(results.items()).hasSize(20);
    }

    @Test
    void asksForZeroConceptsWhenVariablesFillThePage() {
        MonarchClient monarch = searching(page(0, 400));

        TermResults results = service(monarch).terms("hyper", 2, 0);

        verify(monarch).search("hyper", 0, 0);
        assertThat(types(results.items())).containsExactly("BdcVariable", "BdcVariable");
        assertThat(results.total()).isEqualTo(403);
        assertThat(results.hasMore()).isTrue();
    }

    @Test
    void reportsNoFurtherPageAtTheEndOfTheMergedSet() {
        TermResults results = service(searching(page(5, 5))).terms("hyper", 20, 0);

        assertThat(results.items()).hasSize(8);
        assertThat(results.total()).isEqualTo(8);
        assertThat(results.hasMore()).isFalse();
    }

    @Test
    void returnsConceptsOnlyWhenNoVariableMatches() {
        TermResults results = service(searching(page(20, 400))).terms("zzzznotaterm", 20, 0);

        assertThat(types(results.items())).containsOnly("Term");
        assertThat(results.bdcVariableCount()).isZero();
        assertThat(results.total()).isEqualTo(400);
    }

    @Test
    void stillAnnotatesEachConceptWithItsCoverage() {
        ConceptPage upstream = new ConceptPage(List.of(
                new Term("MONDO:0005044", "hypertensive disorder", "biolink:Disease", null,
                        List.of(), "hypertensive disorder (MONDO:0005044)", BdcCoverage.none())), 1, 1);

        TermResults results = service(searching(upstream)).terms("hyper", 20, 0);

        Term concept = (Term) results.items().get(VARIABLES);
        assertThat(concept.bdc().studyCount()).isEqualTo(10);
    }

    @Test
    void doesNotInventAFurtherPageWhenTheMapperDroppedARepeat() {
        // Upstream sent two rows out of a total of two; one was a repeat, so one concept survives.
        ConceptPage deduped = new ConceptPage(List.of(concept(0)), 2, 2);

        TermResults results = service(searching(deduped)).terms("zzzznotaterm", 20, 0);

        assertThat(results.items()).hasSize(1);
        assertThat(results.hasMore()).isFalse();
    }

    @Test
    void addsNothingToAutocomplete() {
        SuggestionPage upstream = new SuggestionPage(List.of(
                new Suggestion("MONDO:0005044", "hypertensive disorder", "biolink:Disease", null,
                        List.of(), "hypertensive disorder (MONDO:0005044)")), 10);
        MonarchClient monarch = mock(MonarchClient.class);
        when(monarch.autocomplete(anyString())).thenReturn(upstream);

        AutocompleteResults results = service(monarch).autocomplete("hyper");

        assertThat(results.items()).hasSize(1);
        assertThat(results.total()).isEqualTo(10);
        assertThat(results.items().getFirst().id()).isEqualTo("MONDO:0005044");
    }
}
