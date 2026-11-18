package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.harmonized;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class HarmonizedVariableLibraryTest {
    private static final String MANIFEST = "classpath:harmonized/catalogs/manifest.json";
    private static final HarmonizedVariableLibrary LIBRARY = HarmonizedVariableLibrary.bundled(
            new HarmonizedVariableProperties(true, MANIFEST, 25));

    @Test void loadsAllConceptsAndPreservesIntegratedIdentities() {
        assertThat(LIBRARY.conceptCount()).isEqualTo(303);
        var c = LIBRARY.search("MONDO:0005044").getFirst();
        assertThat(c.studyCount()).isEqualTo(10);
        assertThat(c.conceptLabel()).isNotBlank();
        assertThat(c.integratedVariables()).singleElement().satisfies(iv -> {
            assertThat(iv.id()).isEqualTo("bdchm:Condition/MONDO:0005044");
            assertThat(iv.studyMappings()).isNotEmpty().allSatisfy(m -> {
                assertThat(m.studyId()).startsWith("bdchm:Study/phs");
                assertThat(m.sourceVariables()).isNotEmpty();
            });
        });
    }
    @Test void keepsDifferentClassesForTheSameConcept() {
        // Producer has two OMOP concepts represented by more than one integrated variable.
        var manifest = new org.springframework.core.io.DefaultResourceLoader();
        var cards = new PortalCatalogLoader(manifest).load(MANIFEST);
        var multiple = cards.stream().filter(c -> c.integratedVariables().size() > 1).toList();
        assertThat(multiple).hasSize(2).allSatisfy(c -> {
            assertThat(c.integratedVariables()).extracting(BdcConcept.IntegratedVariable::id).doesNotHaveDuplicates();
            assertThat(c.bdchmClasses()).hasSize(2).contains("Observation");
            assertThat(c.bdchmType()).isEqualTo(BdchmTypeEnum.observation);
        });
    }
    @Test void keepsLegacyAliasFieldsAndAddsActualSourceVariables() {
        var c = LIBRARY.search("MONDO:0005044").getFirst();
        assertThat(c.variables()).extracting(BdcConcept.BdcCarrier::variableName).contains("hyperten", "hypertension");
        assertThat(c.sourceMappings()).allSatisfy(m -> {
            assertThat(m.bdchmClass()).isEqualTo("Condition");
            assertThat(m.conceptSlot()).isEqualTo("condition_concept");
        });
        var sources = c.integratedVariables().stream().flatMap(v -> v.studyMappings().stream())
                .flatMap(m -> m.sourceVariables().stream()).toList();
        assertThat(sources).anyMatch(BdcConcept.SourceVariable::valueVariable);
        assertThat(sources).anyMatch(s -> !s.valueVariable());
        assertThat(sources).allSatisfy(s -> assertThat(s.id()).startsWith("dbgap:phv"));
    }
    @Test void searchesLabelsAliasesAndExactCurie() {
        assertThat(LIBRARY.search("hypertension")).extracting(BdcConcept::conceptType).contains("MONDO:0005044", "HP:0000822");
        assertThat(LIBRARY.search("bdy_hgt")).isNotEmpty();
        assertThat(LIBRARY.search("mondo:0005044")).hasSize(1);
        assertThat(LIBRARY.search("MONDO:999999999")).isEmpty();
        assertThat(LIBRARY.search(" ")).isEmpty();
        assertThat(LIBRARY.coverageFor("MONDO:0005044").studyCount()).isEqualTo(10);
    }
    @Test void capsAndOrdersByStudyCoverage() {
        var lib = HarmonizedVariableLibrary.bundled(new HarmonizedVariableProperties(true, MANIFEST, 2));
        assertThat(lib.search("hyper")).hasSize(2);
        assertThat(LIBRARY.search("hyper").stream().map(BdcConcept::studyCount).toList())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
    @Test void missingLabelsUseCurieAndNotVariableLabels() {
        var c = LIBRARY.search("ATC:C02").getFirst();
        assertThat(c.conceptLabel()).isNull();
        assertThat(c.displayLabel()).isEqualTo("ATC:C02");
    }
    @Test void disabledLibraryNeedsNoFiles() {
        assertThat(HarmonizedVariableLibrary.disabled().conceptCount()).isZero();
        assertThat(HarmonizedVariableLibrary.disabled().coverageFor("HP:0000822").studyCount()).isZero();
    }
    @Test void failsForMissingManifest() {
        assertThatThrownBy(() -> HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true, "classpath:no-such.json", 25)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot read VarLib");
    }
    @Test void rejectsEmptyCollection(@TempDir Path dir) throws Exception {
        var path = dir.resolve("manifest.json");
        Files.writeString(path, "{\"schema_version\":3,\"release\":\"test\",\"catalogs\":[]}");
        assertThatThrownBy(() -> HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true, path.toUri().toString(), 25)))
                .hasMessageContaining("empty");
    }
    @Test void rejectsInvalidCatalog(@TempDir Path dir) throws Exception {
        var path = dir.resolve("manifest.json");
        Files.writeString(path, """
            {"schema_version":3,"release":"test","catalogs":[
              {"file":"varlib_catalog_test.json","prefix":"TEST"}]}
            """);
        Files.writeString(dir.resolve("varlib_catalog_test.json"), "{}");
        assertThatThrownBy(() -> HarmonizedVariableLibrary.bundled(
                new HarmonizedVariableProperties(true, path.toUri().toString(), 25)))
                .hasMessageContaining("Catalog schema/release mismatch");
    }
}
