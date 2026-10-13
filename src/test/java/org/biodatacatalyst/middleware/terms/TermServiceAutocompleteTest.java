package org.biodatacatalyst.middleware.terms;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.biodatacatalyst.middleware.harmonized.BdcCoverage;
import org.biodatacatalyst.middleware.harmonized.BdcVariable;
import org.biodatacatalyst.middleware.harmonized.HarmonizedVariableLibrary;
import org.biodatacatalyst.middleware.harmonized.HarmonizedVariableProperties;
import org.biodatacatalyst.middleware.monarch.MonarchClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class TermServiceAutocompleteTest {

    private static TermResults.Term concept(String id, String label) {
        return new TermResults.Term(id, label, "biolink:Disease", null, List.of(),
                label + " (" + id + ")", BdcCoverage.none());
    }

    private static AutocompleteResults.Suggestion suggestion(String id, String label) {
        return new AutocompleteResults.Suggestion(id, label, "biolink:Disease", "Description for " + label,
                label + " (" + id + ")", BdcCoverage.none());
    }

    private static TermService service(MonarchClient monarch) {
        return new TermService(monarch, HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true,
                        "classpath*:harmonized/*-ingest/*.yaml",
                        "classpath:harmonized/harmonized_vars.tsv", 25)));
    }

    private static MonarchClient suggesting(AutocompleteResults results) {
        MonarchClient monarch = mock(MonarchClient.class);
        when(monarch.autocomplete(anyString())).thenReturn(results);
        return monarch;
    }

    @Test
    void listsEveryMatchingBdcVariableForAHalfTypedTerm() {
        AutocompleteResults results = service(suggesting(AutocompleteResults.fromMonarch(
                List.of(suggestion("MONDO:0000001", "unrelated")), 1)))
                .autocomplete("hyper");

        assertThat(results.bdcVariables()).extracting("name")
                .containsExactly("hypert_trt", "hyperten", "hypertension");
        // Broadest study coverage first, so the most usable option leads.
        assertThat(results.bdcVariables().getFirst().studies()).hasSize(8);
    }

    @Test
    void annotatesEachSuggestionWithWhatBdcHolds() {
        AutocompleteResults results = service(suggesting(AutocompleteResults.fromMonarch(List.of(
                suggestion("MONDO:0005044", "hypertensive disorder"),
                suggestion("HP:0001250", "Seizure")), 2)))
                .autocomplete("hyper");

        assertThat(results.items().get(0).bdc().studyCount()).isPositive();
        assertThat(results.items().get(0).bdc().studyCount()).isEqualTo(10);
        assertThat(results.items().get(1).bdc().studyCount()).isZero();
        assertThat(results.items().get(1).bdc().studyCount()).isZero();
    }

    @Test
    void leavesTheSuggestionsAsTheyWere() {
        AutocompleteResults results = service(suggesting(AutocompleteResults.fromMonarch(List.of(
                suggestion("HP:0001250", "Seizure"),
                suggestion("MONDO:0005044", "hypertensive disorder")), 2)))
                .autocomplete("hyper");

        assertThat(results.items()).extracting("id")
                .containsExactly("HP:0001250", "MONDO:0005044");
        assertThat(results.total()).isEqualTo(2);
        assertThat(results.items().getFirst().label()).isEqualTo("Seizure");
    }

    @Test
    void returnsBdcVariablesEvenWhenMonarchSuggestsNothing() {
        AutocompleteResults results =
                service(suggesting(AutocompleteResults.fromMonarch(List.of(), 0)))
                        .autocomplete("hyper");

        assertThat(results.items()).isEmpty();
        assertThat(results.bdcVariables()).isNotEmpty();
    }

    @Test
    void returnsBothEmptyForATermNobodyKnows() {
        AutocompleteResults results =
                service(suggesting(AutocompleteResults.fromMonarch(List.of(), 0)))
                        .autocomplete("zzzznotaterm");

        assertThat(results.items()).isEmpty();
        assertThat(results.bdcVariables()).isEmpty();
    }

    @Test
    void acceptsACurieAsTheTypedText() {
        AutocompleteResults results =
                service(suggesting(AutocompleteResults.fromMonarch(List.of(), 0)))
                        .autocomplete("MONDO:0005044");

        assertThat(results.bdcVariables()).extracting("name")
                .containsExactlyInAnyOrder("hyperten", "hypertension");
    }

    @Test
    void reportsNothingFromBdcWhenTheFeatureIsOff() {
        AutocompleteResults results = new TermService(
                suggesting(AutocompleteResults.fromMonarch(
                        List.of(suggestion("MONDO:0005044", "hypertensive disorder")), 1)),
                HarmonizedVariableLibrary.disabled())
                .autocomplete("hyper");

        assertThat(results.bdcVariables()).isEmpty();
        assertThat(results.items().getFirst().bdc().studyCount()).isZero();
    }

    // ------------------------------------------------- the same two forms on the full search

    private static MonarchClient searching(TermResults page) {
        MonarchClient monarch = mock(MonarchClient.class);
        when(monarch.search(anyString(), anyInt(), anyInt())).thenReturn(page);
        return monarch;
    }

    @Test
    void listsMatchingBdcVariablesOnTheFullSearchToo() {
        TermResults results = service(searching(TermResults.fromMonarch(
                List.of(concept("MONDO:0000001", "unrelated")), 4000, 20, 0, true)))
                .terms("hyper", 20, 0);

        assertThat(results.bdcVariables()).extracting("name")
                .containsExactly("hypert_trt", "hyperten", "hypertension");
        assertThat(results.bdcVariables().getFirst().studies()).hasSize(8);
        assertThat(results.items()).extracting("id").containsExactly("MONDO:0000001");
    }

    @Test
    void annotatesEachConceptOnTheFullSearch() {
        TermResults results = service(searching(TermResults.fromMonarch(List.of(
                concept("MONDO:0005044", "hypertensive disorder"),
                concept("HP:0001250", "Seizure")), 2, 20, 0, false)))
                .terms("hypertension", 20, 0);

        assertThat(results.items().get(0).bdc().studyCount()).isPositive();
        assertThat(results.items().get(0).bdc().studyCount()).isEqualTo(10);
        assertThat(results.items().get(1).bdc().studyCount()).isZero();
    }

    @Test
    void leavesTheConceptsAndEnvelopeAsTheyWere() {
        TermResults results = service(searching(TermResults.fromMonarch(List.of(
                concept("HP:0001250", "Seizure"),
                concept("MONDO:0005044", "hypertensive disorder")), 42, 20, 0, true)))
                .terms("hypertension", 20, 0);

        assertThat(results.items()).extracting("id")
                .containsExactly("HP:0001250", "MONDO:0005044");
        assertThat(results.total()).isEqualTo(42);
        assertThat(results.limit()).isEqualTo(20);
        assertThat(results.hasMore()).isTrue();
    }

    @Test
    void returnsTheSameBdcVariablesOnEveryPage() {
        TermResults first = service(searching(TermResults.fromMonarch(List.of(), 4000, 20, 0, true)))
                .terms("hyper", 20, 0);
        TermResults deep = service(searching(TermResults.fromMonarch(List.of(), 4000, 20, 300, true)))
                .terms("hyper", 20, 300);

        assertThat(deep.bdcVariables()).isEqualTo(first.bdcVariables());
        assertThat(deep.bdcVariables()).isNotEmpty();
    }

    @Test
    void agreesWithAutocompleteForTheSameText() {
        List<BdcVariable> fromSearch =
                service(searching(TermResults.fromMonarch(List.of(), 0, 20, 0, false)))
                        .terms("hyper", 20, 0).bdcVariables();
        List<BdcVariable> fromSuggestions =
                service(suggesting(AutocompleteResults.fromMonarch(List.of(), 0)))
                        .autocomplete("hyper").bdcVariables();

        assertThat(fromSearch).isEqualTo(fromSuggestions);
    }

    @Test
    void returnsNothingFromBdcOnTheFullSearchWhenTheFeatureIsOff() {
        TermResults results = new TermService(
                searching(TermResults.fromMonarch(
                        List.of(concept("MONDO:0005044", "hypertensive disorder")), 1, 20, 0, false)),
                HarmonizedVariableLibrary.disabled())
                .terms("hyper", 20, 0);

        assertThat(results.bdcVariables()).isEmpty();
        assertThat(results.items().getFirst().bdc().studyCount()).isZero();
    }
}
