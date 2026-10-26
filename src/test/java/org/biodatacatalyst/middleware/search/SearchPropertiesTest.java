package org.biodatacatalyst.middleware.search;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SearchPropertiesTest {
    private SearchProperties configured(String endpoint, String index) {
        return new SearchProperties(true, URI.create(endpoint), index, "secret",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
    @Test void rejectsUnsafeEndpointAndIndexPaths() {
        for (String endpoint : List.of("ftp://host", "http://user:pass@host", "http://host/path",
                "http://host?x=1", "http://host#fragment")) {
            assertThatThrownBy(() -> configured(endpoint, "metadata")).isInstanceOf(IllegalArgumentException.class);
        }
        for (String index : List.of("*", "a,b", "../_all", "a?x=y", "UPPER", "")) {
            assertThatThrownBy(() -> configured("https://host", index)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void disabledConfigurationNeedsNoEndpointOrIndex() {
        assertThat(new SearchProperties(false, null, null, null, Duration.ofSeconds(1),
                Duration.ofSeconds(2)).enabled()).isFalse();
    }
    @Test void credentialsAreRedacted() {
        assertThat(configured("https://host", "metadata").toString()).doesNotContain("secret");
    }
    @Test void rejectsInvalidTimeout() {
        assertThatThrownBy(() -> new SearchProperties(false, null, null, null, Duration.ZERO,
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
