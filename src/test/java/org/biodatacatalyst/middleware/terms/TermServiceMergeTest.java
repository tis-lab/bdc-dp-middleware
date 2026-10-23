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

    private static final int BDC = 5;

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
                        "classpath:harmonized/harmonized-index.json", 25)));
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
    void fillsTheFirstPageWithBdcConceptsThenUpstream() {
        TermResults results = service(searching(page(20 - BDC, 400))).terms("hyper", 20, 0);

        assertThat(results.items()).hasSize(20);
        assertThat(types(results.items()).subList(0, BDC))
                .containsOnly("BdcConcept");
        assertThat(types(results.items()).subList(BDC, 20)).containsOnly("Term");
        assertThat(results.bdcConceptCount()).isEqualTo(BDC);
        assertThat(results.total()).isEqualTo(400 + BDC);
        assertThat(results.hasMore()).isTrue();
    }

    @Test
    void asksUpstreamOnlyForTheSlotsBdcDidNotTake() {
        MonarchClient monarch = searching(page(20 - BDC, 400));

        service(monarch).terms("hyper", 20, 0);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        verify(monarch).search(anyString(), limit.capture(), offset.capture());
        assertThat(limit.getValue()).isEqualTo(20 - BDC);
        assertThat(offset.getValue()).isZero();
    }

    @Test
    void shiftsTheUpstreamOffsetBackByTheBdcConceptsAlreadyShown() {
        MonarchClient monarch = searching(page(20, 400));

        TermResults results = service(monarch).terms("hyper", 20, 20);

        verify(monarch).search("hyper", 20, 20 - BDC);
        assertThat(types(results.items())).containsOnly("Term");
        assertThat(results.bdcConceptCount()).isEqualTo(BDC);
        assertThat(results.total()).isEqualTo(400 + BDC);
    }

    @Test
    void handlesAPageStartingPartwayThroughTheBdcConcepts() {
        MonarchClient monarch = searching(page(20 - (BDC - 1), 400));

        TermResults results = service(monarch).terms("hyper", 20, 1);

        verify(monarch).search("hyper", 20 - (BDC - 1), 0);
        assertThat(types(results.items()).subList(0, BDC - 1)).containsOnly("BdcConcept");
        assertThat(types(results.items()).get(BDC - 1)).isEqualTo("Term");
        assertThat(results.items()).hasSize(20);
    }

    @Test
    void asksForZeroUpstreamWhenBdcFillsThePage() {
        MonarchClient monarch = searching(page(0, 400));

        TermResults results = service(monarch).terms("hyper", 2, 0);

        verify(monarch).search("hyper", 0, 0);
        assertThat(types(results.items())).containsExactly("BdcConcept", "BdcConcept");
        assertThat(results.total()).isEqualTo(400 + BDC);
        assertThat(results.hasMore()).isTrue();
    }

    @Test
    void reportsNoFurtherPageAtTheEndOfTheMergedSet() {
        TermResults results = service(searching(page(5, 5))).terms("hyper", 20, 0);

        assertThat(results.items()).hasSize(BDC + 5);
        assertThat(results.total()).isEqualTo(BDC + 5);
        assertThat(results.hasMore()).isFalse();
    }

    @Test
    void returnsUpstreamOnlyWhenNoBdcConceptMatches() {
        TermResults results = service(searching(page(20, 400))).terms("zzzznotaterm", 20, 0);

        assertThat(types(results.items())).containsOnly("Term");
        assertThat(results.bdcConceptCount()).isZero();
        assertThat(results.total()).isEqualTo(400);
    }

    @Test
    void stillAnnotatesEachConceptWithItsCoverage() {
        ConceptPage upstream = new ConceptPage(List.of(
                new Term("MONDO:0005044", "hypertensive disorder", "biolink:Disease", null,
                        List.of(), "hypertensive disorder (MONDO:0005044)", BdcCoverage.none())), 1, 1);

        TermResults results = service(searching(upstream)).terms("hyper", 20, 0);

        Term concept = results.items().stream()
                .filter(Term.class::isInstance)
                .map(Term.class::cast)
                .findFirst()
                .orElseThrow();
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
