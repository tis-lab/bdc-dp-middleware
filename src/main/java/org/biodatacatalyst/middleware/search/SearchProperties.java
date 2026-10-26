package org.biodatacatalyst.middleware.search;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("search")
public record SearchProperties(
        @DefaultValue("false") boolean enabled,
        URI endpoint,
        String index,
        String apiKey,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout) {
    public SearchProperties {
        if (enabled) {
            if (endpoint == null || endpoint.getHost() == null
                    || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))
                    || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null
                    || !(endpoint.getRawPath().isEmpty() || "/".equals(endpoint.getRawPath()))) {
                throw new IllegalArgumentException("search.endpoint must be an HTTP(S) origin without credentials, path, query or fragment");
            }
            // One concrete index or alias; no wildcard, comma, path or query injection.
            if (index == null || !index.matches("[a-z0-9][a-z0-9._-]{0,254}")) {
                throw new IllegalArgumentException("search.index must be a single lowercase index or alias");
            }
        }
        for (Duration timeout : new Duration[]{connectTimeout, readTimeout}) {
            if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalArgumentException("search timeouts must be between 1ms and 2m");
            }
        }
        if (apiKey != null && (apiKey.contains("\r") || apiKey.contains("\n"))) {
            throw new IllegalArgumentException("search.api-key must not contain line breaks");
        }
    }

    @Override public String toString() {
        return "SearchProperties[enabled=" + enabled + ", credentials=REDACTED]";
    }
}
