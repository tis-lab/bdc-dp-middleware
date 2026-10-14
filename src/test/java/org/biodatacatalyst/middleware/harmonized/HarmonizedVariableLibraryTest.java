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

    private static BdcVariable variable(HarmonizedVariableLibrary library, String name) {
        return library.search(name).stream()
                .filter(v -> v.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no variable named " + name));
    }

    @Test
    void foldsEveryStudySpecIntoOneVariablePerName() {
        assertThat(library().variableCount()).isEqualTo(179);
        assertThat(library().conceptCount()).isEqualTo(240);
    }

    @Test
    void summarisesAVariableWithoutTheSpecificationDetail() {
        BdcVariable hyperten = variable(library(), "hyperten");

        assertThat(hyperten.label()).isEqualTo("Hypertension");
        assertThat(hyperten.domain()).isEqualTo("DIAGNOSIS");
        assertThat(hyperten.studyCount()).isEqualTo(7);
        assertThat(hyperten.studies())
                .containsExactly("CARDIA", "CHS", "COPDGene", "HCHS", "JHS", "MESA", "WHI");
        assertThat(hyperten.concepts()).containsExactly("MONDO:0005044", "MONDO:0005149");
    }

    /** 49 variables have no QA-table row; label must still be safe to render. */
    @Test
    void fallsBackToTheMachineNameWhenTheTableHasNoRow() {
        BdcVariable hypertension = variable(library(), "hypertension");

        assertThat(hypertension.label()).isEqualTo("hypertension");
        assertThat(hypertension.domain()).isNull();
        assertThat(hypertension.studyCount()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ concept coverage

    @Test
    void countsOnlyTheStudiesThatAssignTheConcept() {
        BdcCoverage hypertension = library().coverageFor("MONDO:0005044");

        assertThat(hypertension.studyCount()).isEqualTo(10);
        assertThat(hypertension.studies()).containsExactly(
                "ARIC", "CARDIA", "CHS", "COPDGene", "FHS", "HCHS", "JHS", "MESA", "SPIROMICS", "WHI");
        assertThat(hypertension.variableNames()).containsExactly("hyperten", "hypertension");
    }

    @Test
    void doesNotLetAVariableInheritStudiesThatAssignADifferentCode() {
        BdcCoverage narrow = library().coverageFor("HP:0012393");

        assertThat(narrow.studyCount()).isEqualTo(1);
        assertThat(narrow.variableNames()).containsExactly("asthma");
        // The variable as a whole is still harmonized in ten studies; the concept is not.
        assertThat(variable(library(), "asthma").studyCount()).isEqualTo(10);
    }

    @Test
    void reportsNoCoverageForAConceptBdcHasNotHarmonized() {
        BdcCoverage none = library().coverageFor("MONDO:9999999");

        assertThat(none.studyCount()).isZero();
        assertThat(none.studies()).isEmpty();
        assertThat(none.variableNames()).isEmpty();
    }

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

    @Test
    void doesNotTreatTransformMachineryAsAConcept() {
        for (String notAConcept : List.of("DEATH_RECORD", "ONESELF", "PRESENT", "Y")) {
            assertThat(library().coverageFor(notAConcept).studyCount()).as(notAConcept).isZero();
        }
    }

    // ------------------------------------------------------------------------ text search

    @Test
    void findsEveryMatchingVariableFromAPartialWord() {
        assertThat(library().search("hyper")).extracting("name")
                .containsExactly("hypert_trt", "hyperten", "hypertension");
    }

    @Test
    void ordersMatchesByStudyCoverage() {
        assertThat(library().search("hyper")).extracting(BdcVariable::studyCount)
                .containsExactly(8, 7, 3);
    }

    /** Without the QA table's label, "height" could never reach a variable named bdy_hgt. */
    @Test
    void matchesOnTheLabelAsWellAsTheMachineName() {
        assertThat(library().search("height")).extracting("name").contains("bdy_hgt");
    }

    @Test
    void matchesSnakeCaseNamesFromSpacedText() {
        assertThat(library().search("blood pressure")).extracting("name").contains("blood_pressure");
    }

    @Test
    void acceptsACurieAsTheQuery() {
        assertThat(library().search("MONDO:0005044")).extracting("name")
                .containsExactlyInAnyOrder("hyperten", "hypertension");
        assertThat(library().search("mondo:0005044")).hasSize(2);
        assertThat(library().search("MONDO:9999999")).isEmpty();
    }

    @Test
    void doesNotMatchAgainstProvenanceOrProse() {
        assertThat(library().search("clinical")).isEmpty();
        assertThat(library().search("measurement of the")).isEmpty();
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

        assertThat(capped.search("hyper")).extracting("name")
                .containsExactly("hypert_trt", "hyperten");
    }

    // ------------------------------------------------------------------------- lifecycle

    @Test
    void reportsNothingWhenDisabled() {
        HarmonizedVariableLibrary disabled = HarmonizedVariableLibrary.disabled();

        assertThat(disabled.variableCount()).isZero();
        assertThat(disabled.search("hyper")).isEmpty();
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

    /** 49 of the 179 variables have no row in the QA table; label falls back to the machine name. */
    @Test
    void fallsBackToTheMachineNameForVariablesWithNoLabel() {
        assertThat(variable(library(), "hypertension").label()).isEqualTo("hypertension");
    }
}
