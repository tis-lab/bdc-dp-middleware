package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("summary")
public record SummaryProperties(String release, Integer minCount) {
    public SummaryProperties {
        if (release == null || release.isBlank() || release.contains("${"))
            throw new IllegalArgumentException("SUMMARY_RELEASE must identify the immutable Elasticsearch data release.");
        if (minCount == null || minCount < 1)
            throw new IllegalArgumentException("SUMMARY_MIN_COUNT must be an explicitly configured integer >= 1.");
    }
}
