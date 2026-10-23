package org.biodatacatalyst.middleware.summary;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.biodatacatalyst.middleware.shared.ApiException;
import static org.assertj.core.api.Assertions.*;

class SummaryServiceTest {
    private SummaryService service() { return new SummaryService(new ExampleSummarySource(new DefaultResourceLoader())); }
    private SummaryModels.SummaryResponse read(String yaml) {
        return new SummaryReader().read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
    private static final String HEADER="release: test\nseed: MONDO:0005148\nmin_count: 10\n";

    @Test void preservesSourceContractAndCounts() {
        var r=service().summary("t2d");
        assertThat(r.filter()).containsExactly("palette:measurement/FAST_GLUC_BLD");
        assertThat(r.studies()).extracting("participant_count").containsExactly(BigInteger.valueOf(480),BigInteger.valueOf(460));
        assertThat(r.studies().getFirst().conditions().getFirst().values().getFirst().participant_count()).isEqualTo(BigInteger.valueOf(90));
        assertThat(r.terms()).hasSize(7);
    }
    @Test void preservesFemaleSourceWithoutInventingMissingSections() {
        var r=service().summary("t2d-female");
        assertThat(r.filter()).contains("palette:demography/sex/FEMALE");
        assertThat(r.studies()).extracting("participant_count").containsExactly(BigInteger.valueOf(230),BigInteger.valueOf(250));
        assertThat(r.relations()).isEmpty();
        assertThat(r.studies().getFirst().participants()).isEmpty();
        assertThat(r.terms()).hasSize(5);
    }
    @Test void sourceSelectionIsAllowlisted() {
        var s=service();
        assertThat(s.sources()).containsExactly("t2d","t2d-female");
        for (var id:Arrays.asList("../t2d","unknown",null))
            assertThatThrownBy(()->s.summary(id)).isInstanceOf(ApiException.class);
    }
    @Test void ignoresIndividualSectionWithoutAddingDtoFields() {
        var r=read(HEADER+"records:\n  private_payload:\n    - secret: DO_NOT_RETURN\n");
        assertThat(r).isEqualTo(read(HEADER));
        assertThat(SummaryModels.SummaryResponse.class.getRecordComponents()).extracting("name")
                .containsExactly("release","seed","filter","min_count","terms","relations","research_studies","studies");
    }
    @Test void rejectsUnknownFieldsAtEveryBoundary() {
        for (var yaml:List.of(HEADER+"future_field: x\n", HEADER+"terms: [{curie: 'MONDO:1', extra: x}]\n",
                HEADER+"studies: [{research_study: 'study:1', participant_count: 10, extra: x}]\n"))
            assertThatThrownBy(()->read(yaml)).isInstanceOf(IllegalStateException.class);
    }
    @Test void validatesRequiredTypesCountsAndSingleDocument() {
        for (var yaml:List.of(HEADER.replace("min_count: 10","min_count: 0"), HEADER.replace("min_count: 10","min_count: 1.5"),
                HEADER.replace("min_count: 10","min_count: '10'"), HEADER.replace("release: test\n",""),
                HEADER+"terms: [null]\n",HEADER+"---\n"+HEADER,
                HEADER+"release: duplicate\n",HEADER+"studies: [{research_study: 'study:1', participant_count: -1}]\n"))
            assertThatThrownBy(()->read(yaml)).isInstanceOf(RuntimeException.class);
    }
    @Test void supportsRecursiveFutureSummaryClassesAndReferenceRanges() {
        String yaml=HEADER+"""
                research_studies: [{id: 'study:1'}]
                studies:
                - research_study: study:1
                  participant_count: 3000000000
                  procedures:
                  - id: summary:procedure
                    procedure_concept: concept:procedure
                    participant_count: 20
                    values:
                    - id: summary:procedure_present
                      participant_count: 10
                      procedure_status: PRESENT
                  drug_exposures:
                  - id: summary:drug
                    drug_concept: concept:drug
                    participant_count: 12
                  measurements:
                  - id: summary:measurement
                    participant_count: 20
                    observation_type: glucose
                    bin_low: 1.1234567890123456789
                    bin_high: 3
                    range_low: 0.5
                    range_high: 5
                """;
        var r=read(yaml);
        assertThat(r.studies().getFirst().participant_count()).isEqualTo(new BigInteger("3000000000"));
        assertThat(r.studies().getFirst().procedures().getFirst().values()).hasSize(1);
        var m=r.studies().getFirst().measurements().getFirst();
        assertThat(m.bin_low().toPlainString()).isEqualTo("1.1234567890123456789");
        assertThat(m.range_low()).isEqualByComparingTo("0.5");
    }
    @Test void rejectsDuplicateStudyAndTermIdentifiersAndInvalidBins() {
        for (var yaml:List.of(HEADER+"terms: [{curie: 'MONDO:1'}, {curie: 'MONDO:1'}]\n",
                HEADER+"research_studies: [{id: 'study:1'}, {id: 'study:1'}]\n",
                HEADER+"studies: [{research_study: 'missing:1', participant_count: 10}]\n",
                HEADER+"studies: [{research_study: 'study:1', participant_count: 10, participants: [{id: 'summary:1', summarized_slot: age_at_index, participant_count: 10, bin_low: 3, bin_high: 2}]}]\n"))
            assertThatThrownBy(()->read(yaml)).isInstanceOf(IllegalStateException.class);
    }

    @Test void conceptSelectionPreservesContractAndPopulationCounts() {
        var s=service(); var full=s.summary("t2d");
        var selected=s.summary("t2d", List.of("MONDO:0005148", "MONDO:0005015"), List.of("MONDO:0005252"));
        assertThat(selected.filter()).isEqualTo(full.filter());
        assertThat(selected.terms()).isEqualTo(full.terms());
        for (int i=0;i<full.studies().size();i++) {
            assertThat(selected.studies().get(i).participant_count()).isEqualTo(full.studies().get(i).participant_count());
            assertThat(selected.studies().get(i).demographics()).isEqualTo(full.studies().get(i).demographics());
            assertThat(selected.studies().get(i).conditions()).extracting("condition_concept").containsExactly("MONDO:0005148");
        }
        assertThat(s.summary("t2d",List.of(),List.of())).isEqualTo(full);
        assertThat(s.summary("t2d",List.of("MONDO:0005148"),List.of("MONDO:0005015")).studies())
                .allMatch(study -> study.conditions().isEmpty());
        assertThat(s.summary("t2d",List.of(),List.of("MONDO:0005252")).studies().getFirst().conditions())
                .extracting("condition_concept").containsExactly("MONDO:0005148","MONDO:0005068");
        assertThat(s.summary("t2d",List.of("OMOP:4156660"),List.of()).studies())
                .allMatch(study -> study.measurements().size()==1 && study.conditions().isEmpty());
    }

    @Test void selectionValidatesCatalogAndDoesNotMutateSource() {
        var s=service();var before=s.summary("t2d");
        assertThat(s.summary("t2d",List.of(" MONDO:0005015 ","MONDO:0005015"),List.of()))
                .isEqualTo(s.summary("t2d",List.of("MONDO:0005015"),List.of()));
        assertThat(s.summary("t2d")).isEqualTo(before);
        assertThatThrownBy(()->s.summary("t2d",List.of("MONDO:0005148"),List.of("MONDO:0005148"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->s.summary("t2d",List.of("UNKNOWN:1"),List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->s.summary("t2d",List.of(),List.of("UNKNOWN:1"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->s.summary("t2d",List.of(" "),List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->s.summary("t2d",null,List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->s.summary("t2d",Collections.nCopies(51,"MONDO:0005148"),List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->s.summary("t2d-female",List.of(),List.of("MONDO:0005252"))).isInstanceOf(ApiException.class);
    }
}
