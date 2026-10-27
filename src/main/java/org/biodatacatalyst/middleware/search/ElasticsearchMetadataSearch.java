package org.biodatacatalyst.middleware.search;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Map;
import org.biodatacatalyst.middleware.shared.ApiException;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.biodatacatalyst.middleware.shared.ApiException.Code.*;

/** Mapping-independent smoke test, not a cohort query translator. */
final class ElasticsearchMetadataSearch implements MetadataSearch {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final SearchProperties properties;
    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    ElasticsearchMetadataSearch(SearchProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.@NonNull HttpURLConnection connection, @NonNull String method)
                    throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override public Execution executeProbe() {
        if (!properties.enabled()) {
            throw new ApiException(UPSTREAM_UNAVAILABLE, "Metadata search is disabled; configure and enable it after the index is available.");
        }
        String base = properties.endpoint().toString().replaceAll("/+$", "");
        URI uri = URI.create(base + "/" + properties.index() + "/_search?allow_partial_search_results=false");
        long started = System.nanoTime();
        try {
            return client.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                            headers.set("Authorization", "ApiKey " + properties.apiKey());
                        }
                    })
                    .body(Map.of("size", 0, "track_total_hits", true,
                            "query", Map.of("match_all", Map.of())))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        // Do not follow redirects or expose backend error bodies/credentials.
                        if (status < 200 || status >= 300) {
                            throw new ApiException(UPSTREAM_UNAVAILABLE,
                                    "Metadata search returned HTTP " + status + ".");
                        }
                        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
                            throw invalidResponse();
                        }
                        JsonNode body;
                        try {
                            body = mapper.readTree(bytes);
                        } catch (RuntimeException malformed) {
                            throw invalidResponse();
                        }
                        validate(body);
                        return new Execution(status, body, (System.nanoTime() - started) / 1_000_000);
                    });
        } catch (ResourceAccessException failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof SocketTimeoutException) {
                    throw new ApiException(UPSTREAM_TIMEOUT, "Metadata search did not respond in time.");
                }
                cause = cause.getCause();
            }
            throw new ApiException(UPSTREAM_UNAVAILABLE, "Metadata search could not be reached.");
        } catch (RestClientException failure) {
            throw new ApiException(UPSTREAM_UNAVAILABLE, "Metadata search request failed.");
        }
    }

    private static void validate(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("timed_out").isBoolean()) {
            throw invalidResponse();
        }
        if (body.path("timed_out").asBoolean()) {
            throw new ApiException(UPSTREAM_TIMEOUT, "Metadata search returned incomplete results after a timeout.");
        }
        JsonNode failed = body.at("/_shards/failed");
        if (!failed.isIntegralNumber() || failed.asLong() < 0) throw invalidResponse();
        if (failed.asLong() != 0) {
            throw new ApiException(UPSTREAM_UNAVAILABLE, "Metadata search returned partial shard results.");
        }
        JsonNode total = body.at("/hits/total/value");
        if (!total.isIntegralNumber() || total.asLong() < 0
                || !"eq".equals(body.at("/hits/total/relation").asString())
                || !body.at("/hits/hits").isArray() || !body.at("/hits/hits").isEmpty()) {
            throw invalidResponse();
        }
    }

    private static ApiException invalidResponse() {
        return new ApiException(UPSTREAM_INVALID_RESPONSE, "Metadata search returned an invalid or unexpected response.");
    }
}
