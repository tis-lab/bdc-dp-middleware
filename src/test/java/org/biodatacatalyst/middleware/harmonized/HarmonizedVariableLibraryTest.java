package org.biodatacatalyst.middleware.harmonized;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarmonizedVariableLibraryTest {

    private static final String INDEX = "classpath:harmonized/harmonized-index.json";

    private static HarmonizedVariableLibrary library() {
        return HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true, INDEX, 25));
    }

    private static BdcConcept concept(String curie) {
        return library().search(curie).stream()
                .filter(c -> c.conceptType().equals(curie))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no concept " + curie));
    }

    @Test
    void loadsEveryConceptFromTheIndex() {
        assertThat(library().conceptCount()).isEqualTo(240);
    }

    @Test
    void keysOnTheConceptAndCarriesItsProvenance() {
        BdcConcept hypertension = concept("MONDO:0005044");

        assertThat(hypertension.conceptLabel()).isEqualTo("Hypertension");
        assertThat(hypertension.displayLabel()).isEqualTo("Hypertension");
        assertThat(hypertension.bdchmType()).isEqualTo("condition");
        assertThat(hypertension.studyCount()).isEqualTo(10);
        assertThat(hypertension.studies()).containsExactly(
                "ARIC", "CARDIA", "CHS", "COPDGene", "FHS", "HCHS", "JHS", "MESA", "SPIROMICS", "WHI");
        assertThat(hypertension.variables()).extracting("variableName")
                .containsExactly("hyperten", "hypertension");
        assertThat(hypertension.variables().getFirst().studies()).hasSize(7);
        assertThat(hypertension.variables().getLast().studies()).hasSize(3);
    }

    @Test
    void keepsSubtypesApart() {
        BdcConcept subtype = concept("MONDO:0005149");

        assertThat(subtype.studyCount()).isEqualTo(2);
        assertThat(subtype.studies()).containsExactly("ARIC", "WHI");
        assertThat(subtype.variables()).extracting("variableName")
                .containsExactly("hyperten", "hypertension");
        // Each carrier assigns it in exactly one study, not in its whole study list.
        assertThat(subtype.variables()).allSatisfy(
                carrier -> assertThat(carrier.studies()).hasSize(1));
    }

    /** Ten studies have an asthma.yaml; one assigns this code. Counting by file name gives ten. */
    @Test
    void countsOnlyTheStudiesThatAssignTheConcept() {
        BdcConcept narrow = concept("HP:0012393");

        assertThat(narrow.studyCount()).isEqualTo(1);
        assertThat(narrow.variables()).singleElement()
                .satisfies(carrier -> assertThat(carrier.variableName()).isEqualTo("asthma"));
    }

    @Test
    void derivesTheBdchmTypeFromTheSpecification() {
        assertThat(concept("MONDO:0005044").bdchmType()).isEqualTo("condition");
        assertThat(concept("OBA:VT0001253").bdchmType()).isEqualTo("observation");
        assertThat(concept("ATC:C02").bdchmType()).isEqualTo("drugexposure");
    }

    @Test
    void namesItsLabelConceptLabel() {
        assertThat(concept("MONDO:0005044").conceptLabel()).isEqualTo("Hypertension");
    }

    /** A concept never displays a file name, even where no label could be derived. */
    @Test
    void neverDisplaysAFileName() {
        assertThat(library().search("hypert")).allSatisfy(concept -> {
            assertThat(concept.displayLabel()).isNotBlank();
            assertThat(concept.displayLabel())
                    .isNotEqualTo(concept.variables().getFirst().variableName());
        });
    }

    // ------------------------------------------------------------------------- lookup

    @Test
    void reportsNoCoverageForAConceptBdcHasNotHarmonized() {
        BdcCoverage none = library().coverageFor("MONDO:9999999");

        assertThat(none.studyCount()).isZero();
        assertThat(none.studies()).isEmpty();
        assertThat(none.variableNames()).isEmpty();
    }

    @Test
    void summarisesCoverageForAnnotation() {
        BdcCoverage coverage = library().coverageFor("MONDO:0005044");

        assertThat(coverage.studyCount()).isEqualTo(10);
        assertThat(coverage.variableNames()).containsExactly("hyperten", "hypertension");
    }

    /** Prefix casing varies by source (MeSH vs MESH); local identifiers do not. */
    @Test
    void normalisesOnlyTheCuriePrefix() {
        assertThat(library().coverageFor("mondo:0005044").studyCount()).isEqualTo(10);
        assertThat(library().coverageFor(" MONDO:0005044 ").studyCount()).isEqualTo(10);
        assertThat(library().coverageFor("MONDO:0005044X").studyCount()).isZero();
    }

    @Test
    void treatsNullAndBlankConceptsAsUnknown() {
        assertThat(library().coverageFor(null).studyCount()).isZero();
        assertThat(library().coverageFor("  ").studyCount()).isZero();
    }

    // ------------------------------------------------------------------------ search

    @Test
    void findsConceptsFromAPartialWord() {
        List<BdcConcept> matches = library().search("hypert");

        assertThat(matches).isNotEmpty();
        assertThat(matches).extracting("conceptType").contains("MONDO:0005044", "ATC:C02");
        assertThat(matches).extracting("displayLabel")
                .contains("Hypertension", "Hypertension treatment");
    }

    @Test
    void ordersMatchesByStudyCoverage() {
        List<Integer> counts = library().search("hypert").stream()
                .map(BdcConcept::studyCount)
                .toList();

        assertThat(counts).isSortedAccordingTo((a, b) -> Integer.compare(b, a));
    }

    @Test
    void stillMatchesOnAVariableName() {
        assertThat(library().search("bdy_hgt")).extracting("conceptType")
                .contains("OBA:VT0001253");
    }

    @Test
    void acceptsACurieAsTheQuery() {
        assertThat(library().search("MONDO:0005044")).singleElement()
                .satisfies(c -> assertThat(c.studyCount()).isEqualTo(10));
        assertThat(library().search("mondo:0005044")).hasSize(1);
        assertThat(library().search("MONDO:9999999")).isEmpty();
    }

    @Test
    void ignoresFragmentsTooShortToBeEvidence() {
        assertThat(library().search("car")).isEmpty();
        assertThat(library().search("a")).isEmpty();
        assertThat(library().search("  ")).isEmpty();
    }

    @Test
    void capsTheNumberOfMatches() {
        HarmonizedVariableLibrary capped = HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true, INDEX, 2));

        assertThat(capped.search("hypert")).hasSize(2);
    }

    // ------------------------------------------------------------------------- lifecycle

    @Test
    void reportsNothingWhenDisabled() {
        HarmonizedVariableLibrary disabled = HarmonizedVariableLibrary.disabled();

        assertThat(disabled.conceptCount()).isZero();
        assertThat(disabled.search("hypert")).isEmpty();
        assertThat(disabled.coverageFor("MONDO:0005044").studyCount()).isZero();
    }

    @Test
    void failsFastWhenTheIndexIsMissing() {
        assertThatThrownBy(() -> HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true, "classpath:harmonized/no-such.json", 25)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("build_harmonized_index.py");
    }

    @Test
    void failsFastWhenTheIndexIsNotAnIndex() {
        assertThatThrownBy(() -> HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true, "classpath:harmonized/README.md", 25)))
                .isInstanceOf(IllegalStateException.class);
    }
}
