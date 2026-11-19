package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.postgres;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("postgres-search")
public record PostgresProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean syntheticDataConfirmed,
        @DefaultValue("") String url,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("") String release,
        @DefaultValue("0") int minCount,
        @DefaultValue("15") int queryTimeoutSeconds,
        @DefaultValue("10000") int maxAggregateRows) {
    public PostgresProperties {
        if (enabled) {
            if (!syntheticDataConfirmed)
                throw new IllegalArgumentException("Postgres search is restricted to confirmed synthetic data until disclosure functions are available.");
            if (url == null || !url.startsWith("jdbc:postgresql:") || username == null || username.isBlank())
                throw new IllegalArgumentException("Configure POSTGRES_SEARCH_URL and POSTGRES_SEARCH_USERNAME.");
            if (release == null || release.isBlank() || minCount < 1)
                throw new IllegalArgumentException("Configure POSTGRES_SEARCH_RELEASE and POSTGRES_SEARCH_MIN_COUNT >= 1.");
        }
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 120 || maxAggregateRows < 1 || maxAggregateRows > 100000)
            throw new IllegalArgumentException("Invalid PostgreSQL query limits.");
    }
}
