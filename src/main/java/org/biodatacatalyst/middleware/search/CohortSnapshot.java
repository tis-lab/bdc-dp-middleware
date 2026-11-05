package org.biodatacatalyst.middleware.search;

import java.math.BigInteger;
import java.util.List;

/** Interim UI snapshot, deliberately separate from the per-study SummaryResponse. */
public record CohortSnapshot(
        BigInteger participantCount,
        BigInteger totalParticipantCount,
        BigInteger studyCount,
        BigInteger totalStudyCount,
        BigInteger datasetCount,
        BigInteger totalDatasetCount,
        String indexRelease,
        String lastUpdated,
        List<String> unavailableFields) {
    static CohortSnapshot counts(BigInteger matching, BigInteger total) {
        return new CohortSnapshot(matching, total, null, null, null, null, null, null,
                List.of("studyCount", "totalStudyCount", "datasetCount", "totalDatasetCount",
                        "indexRelease", "lastUpdated"));
    }
}
