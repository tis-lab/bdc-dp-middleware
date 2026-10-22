package org.biodatacatalyst.middleware.summary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.biodatacatalyst.middleware.shared.ApiException;
import org.biodatacatalyst.middleware.summary.CohortQuery.Criterion;
import org.biodatacatalyst.middleware.summary.SummaryModels.CohortSnapshot;
import org.biodatacatalyst.middleware.summary.SummaryModels.StudyCounts;
import org.biodatacatalyst.middleware.summary.SummaryModels.SummaryResponse;

import static org.biodatacatalyst.middleware.shared.ApiException.Code.BAD_USER_INPUT;

@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    static final int MAX_CONCEPT_LENGTH = 200;

    static final int MAX_CRITERIA = 50;

    private final SummarySource source;

    public SummaryService(SummarySource source) {
        this.source = source;
    }

    public SummaryResponse summary(CohortQuery query) {
        List<Criterion> criteria = validated(query);

        List<String> present = criteria.stream()
                .filter(Criterion::isPresent)
                .map(Criterion::concept)
                .toList();

        log.debug("Cohort summary for {} criteria, {} of them PRESENT",
                criteria.size(), present.size());

        if (present.isEmpty()) {
            throw new ApiException(BAD_USER_INPUT,
                    "At least one criterion must be PRESENT. Absence alone does not identify a "
                            + "cohort, and is not applied yet in any case.");
        }

        SummaryResponse found = source.find(present)
                .orElseThrow(() -> new ApiException(BAD_USER_INPUT,
                        // Naming what exists matters while this is served from fixtures: without
                        // it a caller cannot tell a wrong concept from an unimplemented one.
                        "No summary is available for those criteria. This endpoint currently serves "
                                + "example data only; available concepts are: "
                                + String.join(" | ", source.available()) + "."));

        return found.with(snapshot(found), notices(found, criteria));
    }

    private static CohortSnapshot snapshot(SummaryResponse response) {
        int participants = response.studies().stream()
                .map(StudyCounts::participantCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        long contributing = response.studies().stream()
                .map(StudyCounts::participantCount)
                .filter(count -> count != null && count > 0)
                .count();
        return new CohortSnapshot(participants, (int) contributing,
                response.minCount() == null ? 0 : response.minCount());
    }

    private static List<String> notices(SummaryResponse response, List<Criterion> criteria) {
        List<String> notices = new ArrayList<>();
        notices.add("Counts are illustrative example data, not derived from participant records.");

        List<String> absent = criteria.stream()
                .filter(criterion -> !criterion.isPresent())
                .map(Criterion::concept)
                .toList();
        if (!absent.isEmpty()) {
            notices.add("ABSENT criteria were not applied: " + String.join(", ", absent)
                    + ". The example data holds no exclusions, so these counts ignore them.");
        }

        List<String> valued = criteria.stream()
                .filter(criterion -> criterion.value() != null && !criterion.value().isEmpty())
                .map(criterion -> criterion.concept() + " " + criterion.value().describe())
                .toList();
        if (!valued.isEmpty()) {
            notices.add("Value filters were not applied: " + String.join(", ", valued)
                    + ". The example data holds pre-computed bins rather than values, so a "
                    + "threshold cannot be evaluated against it.");
        }

        List<String> unused = criteria.stream()
                .filter(Criterion::isPresent)
                .map(Criterion::concept)
                .filter(concept -> !concept.equals(response.seed())
                        && !response.filter().contains(concept))
                .toList();
        if (!unused.isEmpty()) {
            notices.add("These concepts did not narrow the counts: " + String.join(", ", unused)
                    + ". The example data covers " + String.join(" | ", covered(response)) + ".");
        }
        return notices;
    }

    private static List<String> covered(SummaryResponse response) {
        List<String> parts = new ArrayList<>();
        parts.add(response.seed());
        parts.addAll(response.filter());
        return parts;
    }

    private static List<Criterion> validated(CohortQuery query) {
        if (query == null || query.criteria().isEmpty()) {
            throw new ApiException(BAD_USER_INPUT,
                    "query.criteria must contain at least one criterion.");
        }
        List<Criterion> criteria = query.criteria();
        if (criteria.size() > MAX_CRITERIA) {
            throw new ApiException(BAD_USER_INPUT,
                    "query.criteria must contain at most " + MAX_CRITERIA + " entries.");
        }
        for (Criterion criterion : criteria) {
            String concept = criterion.concept();
            if (concept == null || concept.isBlank()) {
                throw new ApiException(BAD_USER_INPUT, "a criterion's concept must not be blank.");
            }
            if (concept.strip().length() > MAX_CONCEPT_LENGTH) {
                throw new ApiException(BAD_USER_INPUT,
                        "a concept must contain at most " + MAX_CONCEPT_LENGTH + " characters.");
            }
            ValueFilterChecks.validate(criterion);
        }
        return criteria.stream()
                .map(criterion -> new Criterion(criterion.concept().strip(),
                        criterion.occurrence(), criterion.value()))
                .toList();
    }

    private static final class ValueFilterChecks {

        static void validate(Criterion criterion) {
            CohortQuery.ValueFilter value = criterion.value();
            if (value == null || value.isEmpty()) {
                return;
            }
            boolean bounded = value.min() != null || value.max() != null;
            if (bounded && value.equals() != null) {
                throw new ApiException(BAD_USER_INPUT,
                        "a value filter cannot combine equals with min or max.");
            }
            if (bounded && value.unit() == null) {
                throw new ApiException(BAD_USER_INPUT,
                        "a value filter with min or max must give a unit.");
            }
            if (value.min() != null && value.max() != null
                    && value.min().compareTo(value.max()) >= 0) {
                throw new ApiException(BAD_USER_INPUT,
                        "a value filter's min must be below its max.");
            }
        }
    }
}
