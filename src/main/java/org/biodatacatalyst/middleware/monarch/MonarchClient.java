package org.biodatacatalyst.middleware.monarch;

import java.net.SocketTimeoutException;
import java.net.URI;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import org.biodatacatalyst.middleware.shared.ApiException;

import static org.biodatacatalyst.middleware.shared.ApiException.Code.UPSTREAM_TIMEOUT;
import static org.biodatacatalyst.middleware.shared.ApiException.Code.UPSTREAM_UNAVAILABLE;

@Component
public class MonarchClient {

    private static final Logger log = LoggerFactory.getLogger(MonarchClient.class);

    private final RestClient client;
    private final MonarchProperties properties;
    private final MonarchResponseMapper mapper;

    public MonarchClient(MonarchProperties properties, MonarchResponseMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());

        this.client = RestClient.builder().requestFactory(factory).build();

        log.info("Monarch client ready: baseUrl={}, connectTimeout={}, readTimeout={}",
                properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }

    /** Full concept search, paginated and ranked. */
    public ConceptPage search(String query, int limit, int offset) {
        return mapper.map(fetch(searchUri(query, limit, offset), "search"), limit, offset);
    }

    public SuggestionPage autocomplete(String query) {
        return mapper.mapAutocomplete(fetch(autocompleteUri(query), "autocomplete"));
    }

    private JsonNode fetch(URI uri, String route) {
        try {
            return client.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (_, response) -> {
                        log.warn("Monarch {} returned HTTP {}", route, response.getStatusCode().value());
                        throw new ApiException(UPSTREAM_UNAVAILABLE, "Monarch could not complete the search.");
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException) {
                    log.warn("Monarch {} timed out after {}", route, properties.readTimeout());
                    throw new ApiException(UPSTREAM_TIMEOUT, "Monarch did not respond in time.");
                }
            }
            log.warn("Monarch {} could not connect", route);
            throw new ApiException(UPSTREAM_UNAVAILABLE, "Monarch is currently unavailable.");
        } catch (RestClientException failure) {
            log.warn("Monarch {} body could not be decoded", route);
            throw MonarchResponseMapper.malformed();
        }
    }

    private URI autocompleteUri(String query) {
        return UriComponentsBuilder.fromUri(properties.baseUrl())
                .pathSegment("autocomplete")
                .queryParam("q", "{query}")
                .encode()
                .buildAndExpand(query)
                .toUri();
    }

    private URI searchUri(String query, int limit, int offset) {
        return UriComponentsBuilder.fromUri(properties.baseUrl())
                .pathSegment("search")
                .queryParam("q", "{query}")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .encode()
                .buildAndExpand(query)
                .toUri();
    }
}
