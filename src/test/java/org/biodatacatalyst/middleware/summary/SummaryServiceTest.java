package org.biodatacatalyst.middleware.summary;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import org.biodatacatalyst.middleware.shared.ApiException;
import org.biodatacatalyst.middleware.summary.CohortQuery.Criterion;
import org.biodatacatalyst.middleware.summary.CohortQuery.ValueFilter;
import org.biodatacatalyst.middleware.summary.SummaryModels.ConditionSummary;
import org.biodatacatalyst.middleware.summary.SummaryModels.DemographySummary;
import org.biodatacatalyst.middleware.summary.SummaryModels.MeasurementObservationSummary;
import org.biodatacatalyst.middleware.summary.SummaryModels.StudyCounts;
import org.biodatacatalyst.middleware.summary.SummaryModels.SummaryResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryServiceTest {

    private static final String SEED = "MONDO:0005148";
    private static final String GLUCOSE = "palette:measurement/FAST_GLUC_BLD";
    private static final String FEMALE = "palette:demography/sex/FEMALE";

    private static SummaryService service() {
        return new SummaryService(new ExampleSummarySource(new DefaultResourceLoader()));
    }

    private static CohortQuery present(String... concepts) {
        return new CohortQuery(java.util.Arrays.stream(concepts)
                .map(concept -> new Criterion(concept, "PRESENT", null))
                .toList());
    }

    private static StudyCounts study(SummaryResponse response) {
        return response.studies().stream()
                .filter(s -> s.researchStudy().equals("palette:study_one"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no study " + "palette:study_one"));
    }

    private static DemographySummary demographic(StudyCounts study, String slot) {
        return study.demographics().stream()
                .filter(d -> d.summarizedSlot().equals(slot))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + slot + " summary"));
    }

    @Test
    void readsTheEnvelopeOfTheUnfilteredExample() {
        SummaryResponse response = service().summary(present(SEED));

        assertThat(response.release()).isEqualTo("example-release");
        assertThat(response.seed()).isEqualTo(SEED);
        assertThat(response.filter()).containsExactly(GLUCOSE);
        assertThat(response.snapshot().estimatedParticipants()).isEqualTo(940);
        assertThat(response.snapshot().studyCount()).isEqualTo(2);
        assertThat(response.snapshot().minCount()).isEqualTo(10);
        assertThat(response.minCount()).isEqualTo(10);
        assertThat(response.terms()).hasSize(7);
        assertThat(response.studies()).hasSize(2);
    }

    @Test
    void carriesEveryTermItRefersTo() {
        SummaryResponse response = service().summary(present(SEED));

        assertThat(response.terms()).extracting("curie").contains(SEED, "OMOP:4156660");
        assertThat(response.terms()).filteredOn(t -> t.curie().equals(SEED))
                .singleElement()
                .satisfies(term -> {
                    assertThat(term.label()).isEqualTo("type 2 diabetes mellitus");
                    assertThat(term.vocabulary()).isEqualTo("MONDO");
                    assertThat(term.parents()).containsExactly("MONDO:0005015");
                });
        // The vocabulary is not always the prefix: an OMOP concept id wraps another vocabulary's.
        assertThat(response.terms()).filteredOn(t -> t.curie().equals("OMOP:4156660"))
                .singleElement()
                .satisfies(term -> assertThat(term.vocabulary()).isEqualTo("SNOMED"));
    }

    @Test
    void readsRelationsWithTheirSource() {
        SummaryResponse response = service().summary(present(SEED));

        assertThat(response.relations()).singleElement().satisfies(relation -> {
            assertThat(relation.subject()).isEqualTo("MONDO:0005015");
            assertThat(relation.predicate()).isEqualTo("biolink:has_biomarker");
            assertThat(relation.object()).isEqualTo("OMOP:4156660");
            // A curated link must never pass for a published one.
            assertThat(relation.relationSource()).isEqualTo("CURATED");
        });
    }

    @Test
    void readsStudyMetadataAlongsideTheCounts() {
        SummaryResponse response = service().summary(present(SEED));

        assertThat(response.researchStudies()).hasSize(2);
        assertThat(response.researchStudies().getFirst().name()).isEqualTo("Example Study One");
        assertThat(response.researchStudies().getFirst().principalInvestigator())
                .containsExactly("Example Investigator");
    }

    @Test
    void readsNestedCountsAndTheirSplits() {
        StudyCounts one = study(service().summary(present(SEED)));

        assertThat(one.participantCount()).isEqualTo(480);

        DemographySummary sex = demographic(one, "sex");
        assertThat(sex.participantCount()).isEqualTo(480);
        assertThat(sex.values()).extracting("sex", "participantCount")
                .containsExactly(tuple("FEMALE", 230), tuple("MALE", 245));
    }

    @Test
    void keepsRaceAsAList() {
        StudyCounts one = study(service().summary(present(SEED)));

        assertThat(demographic(one, "race").values().getFirst().race()).containsExactly("WHITE");
    }

    @Test
    void doesNotInventCountsForWhatTheSourceLeftOut() {
        StudyCounts one = study(service().summary(present(SEED)));

        ConditionSummary t2d = one.conditions().stream()
                .filter(c -> c.conditionConcept().equals(SEED))
                .findFirst().orElseThrow();
        assertThat(t2d.participantCount()).isEqualTo(470);
        assertThat(t2d.values()).extracting("conditionStatus", "participantCount")
                .containsExactly(tuple("PRESENT", 90), tuple("ABSENT", 380));
        // recordCount is set only where it differs from the participant count.
        assertThat(t2d.values().getFirst().recordCount()).isEqualTo(215);
        assertThat(t2d.values().getLast().recordCount()).isNull();

        DemographySummary sex = demographic(one, "sex");
        assertThat(sex.values()).extracting("participantCount").doesNotContain(5);
    }

    @Test
    void readsOpenEndedBins() {
        StudyCounts one = study(service().summary(present(SEED)));

        MeasurementObservationSummary glucose = one.measurements().getFirst();
        assertThat(glucose.observationType()).isEqualTo("FAST_GLUC_BLD");
        assertThat(glucose.participantCount()).isEqualTo(480);
        assertThat(glucose.recordCount()).isEqualTo(1830);

        assertThat(glucose.values().getFirst().binLow()).isNull();
        assertThat(glucose.values().getFirst().binHigh()).isEqualByComparingTo("70");
        assertThat(glucose.values().getLast().binLow()).isEqualByComparingTo("126");
        assertThat(glucose.values().getLast().binHigh()).isNull();
        assertThat(glucose.values().getFirst().binUnit()).isEqualTo("mg/dL");
    }

    @Test
    void agreesWithTheUnfilteredResponseWhereTheyOverlap() {
        SummaryResponse all = service().summary(present(SEED));
        SummaryResponse women = service().summary(present(SEED, FEMALE));

        int femaleInAll = demographic(study(all), "sex").values().stream()
                .filter(v -> "FEMALE".equals(v.sex()))
                .findFirst().orElseThrow().participantCount();

        assertThat(study(women).participantCount()).isEqualTo(femaleInAll);
        assertThat(women.release()).isEqualTo(all.release());
    }

    @Test
    void doesNotCareAboutCriterionOrder() {
        SummaryResponse ordered = service().summary(present(SEED, FEMALE));
        SummaryResponse reversed = service().summary(present(FEMALE, SEED));

        assertThat(reversed.studies().getFirst().participantCount())
                .isEqualTo(ordered.studies().getFirst().participantCount());
    }

    @Test
    void narrowsTheSnapshotWhenABreakdownValueIsACriterion() {
        assertThat(service().summary(present(SEED)).snapshot().estimatedParticipants())
                .isEqualTo(940);
        assertThat(service().summary(present(SEED, FEMALE))
                .snapshot().estimatedParticipants()).isEqualTo(480);
    }

    @Test
    void saysSoWhenAnAbsentCriterionWasNotApplied() {
        SummaryResponse response = service().summary(new CohortQuery(List.of(
                new Criterion(SEED, "PRESENT", null),
                new Criterion("MONDO:0005068", "ABSENT", null))));

        assertThat(response.notices()).anySatisfy(notice ->
                assertThat(notice).contains("ABSENT criteria were not applied", "MONDO:0005068"));
        // The counts ignore it, which is exactly why the notice has to be there.
        assertThat(response.snapshot().estimatedParticipants()).isEqualTo(940);
    }

    @Test
    void saysSoWhenAValueFilterWasNotApplied() {
        SummaryResponse response = service().summary(new CohortQuery(List.of(
                new Criterion(SEED, "PRESENT", null),
                new Criterion("palette:measurement/FAST_GLUC_BLD", "PRESENT",
                        new ValueFilter(new java.math.BigDecimal("126"), null, "mg/dL", null)))));

        assertThat(response.notices()).anySatisfy(notice ->
                assertThat(notice).contains("Value filters were not applied", ">= 126 mg/dL"));
    }

    @Test
    void rejectsValueFiltersThatCannotMeanAnything() {
        assertThatThrownBy(() -> service().summary(new CohortQuery(List.of(
                new Criterion(SEED, "PRESENT",
                        new ValueFilter(new java.math.BigDecimal("1"), null, null, null))))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must give a unit");

        assertThatThrownBy(() -> service().summary(new CohortQuery(List.of(
                new Criterion(SEED, "PRESENT", new ValueFilter(
                        new java.math.BigDecimal("10"), new java.math.BigDecimal("5"), "mg/dL", null))))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("min must be below its max");

        assertThatThrownBy(() -> service().summary(new CohortQuery(List.of(
                new Criterion(SEED, "PRESENT", new ValueFilter(
                        new java.math.BigDecimal("1"), null, "mg/dL", "FEMALE"))))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot combine equals");
    }

    @Test
    void rejectsAQueryWithNoPresentCriterion() {
        assertThatThrownBy(() -> service().summary(new CohortQuery(List.of(
                new Criterion(SEED, "ABSENT", null)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("At least one criterion must be PRESENT");
    }

    @Test
    void alwaysSaysTheCountsAreIllustrative() {
        assertThat(service().summary(present(SEED)).notices())
                .anySatisfy(notice -> assertThat(notice).contains("illustrative example data"));
    }

    @Test
    void saysSoWhenAConceptDidNotNarrowAnything() {
        SummaryResponse response = service().summary(present(SEED, "MONDO:0005068"));

        assertThat(response.notices()).anySatisfy(notice ->
                assertThat(notice).contains("did not narrow the counts", "MONDO:0005068"));
    }

    @Test
    void exposesTheParticipantRecords() {
        SummaryResponse response = service().summary(present(SEED));

        assertThat(response.records()).isNotNull();
        assertThat(response.records().recordConditions()).singleElement().satisfies(record -> {
            assertThat(record.conditionConcept()).isEqualTo(SEED);
            assertThat(record.conditionProvenance()).isEqualTo("PATIENT_SELF_REPORT");
            assertThat(record.conditionStatus()).isEqualTo("PRESENT");
            assertThat(record.relationshipToParticipant()).isEqualTo("ONESELF");
            assertThat(record.associatedParticipant()).isEqualTo("palette:example/participant-1");
        });
        assertThat(response.records().recordDemographics()).singleElement().satisfies(record -> {
            assertThat(record.sex()).isEqualTo("FEMALE");
            assertThat(record.race()).containsExactly("WHITE");
            assertThat(record.ethnicity()).isEqualTo("NOT_HISPANIC_OR_LATINO");
        });
        assertThat(response.records().recordMeasurements()).singleElement().satisfies(record -> {
            assertThat(record.observationType()).isEqualTo("FAST_GLUC_BLD");
            assertThat(record.ageAtObservation()).isEqualTo(22280);
            // BDCHM v1.3.0 does not declare the value inlined, so there is nothing to carry yet.
            assertThat(record.valueQuantity()).isNull();
        });
    }

    @Test
    void returnsNoRecordsWhenTheSourceHasNone() {
        assertThat(service().summary(present(SEED, FEMALE)).records()).isNull();
    }

    @Test
    void returnsEmptyListsForTheBlocksTheExamplesDoNotCover() {
        StudyCounts one = study(service().summary(present(SEED)));

        assertThat(one.procedures()).isEmpty();
        assertThat(one.drugExposures()).isEmpty();
    }

    @Test
    void saysWhatIsAvailableWhenNothingMatches() {
        assertThatThrownBy(() -> service().summary(present("MONDO:9999999")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("example data only")
                .hasMessageContaining(SEED);
    }

    @Test
    void rejectsAnEmptyQueryBeforeLookingAnythingUp() {
        assertThatThrownBy(() -> service().summary(new CohortQuery(List.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one criterion");
    }

    @Test
    void rejectsABlankConcept() {
        assertThatThrownBy(() -> service().summary(present(SEED, " ")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("concept must not be blank");
    }

    @Test
    void treatsAMissingOccurrenceAsPresent() {
        SummaryResponse response = service().summary(
                new CohortQuery(List.of(new Criterion(SEED, null, null))));

        assertThat(response.snapshot().estimatedParticipants()).isEqualTo(940);
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
