package org.biodatacatalyst.middleware.monarch;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("monarch")
public record MonarchProperties(

        @DefaultValue("https://api.monarchinitiative.org/v3/api") URI baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout) {

    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(2);

    public MonarchProperties {
        if (baseUrl == null
                || baseUrl.getHost() == null
                || !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))
                // Credentials in a base URL would end up in logs and in every outgoing request;
                // a query or fragment would be silently dropped when the search path is appended.
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "monarch.base-url must be an HTTP(S) base URL without credentials, query or fragment");
        }
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
    }

    private static void validateTimeout(Duration duration) {
        if (duration == null || duration.toMillis() < 1 || duration.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Monarch timeouts must be between 1ms and 2m");
        }
    }
}
