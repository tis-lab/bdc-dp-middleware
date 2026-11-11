package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.search;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.*;

class ElasticsearchMetadataSearchTest {
    static final String OK = """
        {"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
         "hits":{"total":{"value":0,"relation":"eq"},"hits":[]}}
        """;
    static final String COUNTS = OK.replace("\"value\":0", "\"value\":500").strip();
    static String countResponse() {
        String base = COUNTS;
        return base.substring(0, base.length() - 1) + """
            ,"aggregations":{"population":{"doc_count":1000},"counts":{"doc_count_error_upper_bound":0,
            "sum_other_doc_count":3,"buckets":[{"key":"PRESENT|ONESELF|MONDO:0005252","doc_count":12}]}}}
            """;
    }
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> reply = new AtomicReference<>(OK);
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicInteger delayMillis = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString());
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try (exchange) {
                Thread.sleep(delayMillis.get());
                byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private SearchProperties properties(boolean enabled, Duration timeout) {
        return new SearchProperties(enabled,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                "metadata-test", "test-only-key", Duration.ofSeconds(1), timeout);
    }
    private MetadataSearch client() {
        return new ElasticsearchMetadataSearch(properties(true, Duration.ofSeconds(2)));
    }
    private void failsWith(ApiException.Code code) {
        assertThatThrownBy(() -> client().executeProbe()).isInstanceOfSatisfying(
                ApiException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }

    @Test void sendsQueryToActualListeningPortAndAcceptsZeroHits() {
        var result = client().executeProbe();
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/metadata-test/_search?allow_partial_search_results=false");
        assertThat(authorization.get()).isEqualTo("ApiKey test-only-key");
        var sent = JsonMapper.builder().build().readTree(requestBody.get());
        assertThat(sent.path("size").asInt()).isZero();
        assertThat(sent.path("track_total_hits").asBoolean()).isTrue();
        assertThat(sent.at("/query/match_all").isObject()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.body().at("/hits/total/value").asInt()).isZero();
        assertThat(result.elapsedMillis()).isGreaterThanOrEqualTo(0);
    }
    @Test void returnsAnExactNonzeroCountWithoutCredentials() {
        reply.set(OK.replace("\"value\":0", "\"value\":500"));
        var configured = properties(true, Duration.ofSeconds(2));
        var search = new ElasticsearchMetadataSearch(new SearchProperties(true,
                configured.endpoint(), "participant_1", null,
                configured.connectTimeout(), configured.readTimeout()));
        assertThat(search.executeProbe().body().at("/hits/total/value").asInt()).isEqualTo(500);
        assertThat(path.get()).isEqualTo("/participant_1/_search?allow_partial_search_results=false");
        assertThat(authorization.get()).isNull();
    }
    @Test void queriesTheSuppliedKeywordAndMapsBackendBuckets() {
        reply.set(countResponse());
        for (String keyword : List.of("PRESENT|ONESELF|MONDO:0005148", "ABSENT|ONESELF|MONDO:0005252")) {
            var result = new SearchProbeController(client()).searchConditions(keyword, null);
            var sent = JsonMapper.builder().build().readTree(requestBody.get());
            assertThat(sent.path("size").asInt()).isZero();
            assertThat(sent.path("track_total_hits").asBoolean()).isTrue();
            assertThat(sent.at("/query/bool/filter/0/terms/_conditions/0").asString()).isEqualTo(keyword);
            assertThat(sent.at("/aggs/counts/terms/exclude/0").asString()).isEqualTo(keyword);
            assertThat(result.keyword()).isEqualTo(keyword);
            assertThat(result.totalHits()).isEqualTo("500");
            assertThat(result.buckets()).containsExactly(new SearchResponseMapper.ConditionCount(
                    "PRESENT|ONESELF|MONDO:0005252", "12"));
            assertThat(result.sumOtherDocCount()).isEqualTo("3");
        }
    }
    @Test void sendsOrWithinEachStatusAndAndBetweenGroups() {
        reply.set(countResponse());
        var keys = List.of("PRESENT|ONESELF|MONDO:1", "PRESENT|ONESELF|HP:2",
                "ABSENT|ONESELF|MONDO:3", "ABSENT|ONESELF|HP:4", "PRESENT|ONESELF|MONDO:1");
        var result = new SearchProbeController(client()).searchConditions(null, keys);
        var sent = JsonMapper.builder().build().readTree(requestBody.get());
        assertThat(sent.at("/query/bool/filter").size()).isEqualTo(2);
        assertThat(sent.at("/query/bool/filter/0/terms/_conditions").toString())
                .isEqualTo("[\"PRESENT|ONESELF|MONDO:1\",\"PRESENT|ONESELF|HP:2\"]");
        assertThat(sent.at("/query/bool/filter/1/terms/_conditions").toString())
                .isEqualTo("[\"ABSENT|ONESELF|MONDO:3\",\"ABSENT|ONESELF|HP:4\"]");
        assertThat(sent.at("/aggs/population/global").isObject()).isTrue();
        assertThat(sent.has("post_filter")).isFalse();
        assertThat(result.keywords()).hasSize(4);
        assertThat(result.snapshot().participantCount()).isEqualTo(java.math.BigInteger.valueOf(500));
        assertThat(result.snapshot().totalParticipantCount()).isEqualTo(java.math.BigInteger.valueOf(1000));
        assertThat(result.snapshot().studyCount()).isNull();
        assertThat(result.snapshot().datasetCount()).isNull();
    }
    @Test void rejectsInvalidListsAndAmbiguousArgumentsWithoutNetwork() {
        var controller = new SearchProbeController(client());
        assertThatThrownBy(() -> controller.searchConditions(null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.searchConditions("PRESENT|ONESELF|HP:1", List.of("ABSENT|ONESELF|HP:2")))
                .isInstanceOf(ApiException.class);
        for (var keys : List.of(List.<String>of(), java.util.Collections.nCopies(51, "PRESENT|ONESELF|HP:1"),
                Collections.singletonList((String) null), List.of("UNKNOWN|ONESELF|HP:1"),
                List.of("PRESENT|MOTHER|HP:1"), List.of("PRESENT|ONESELF|HP:*"))) {
            assertThatThrownBy(() -> client().executeConditions(keys)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(BAD_USER_INPUT));
        }
        assertThat(path.get()).isNull();
    }
    @Test void rejectsMissingOrInconsistentPopulationCount() {
        for (String bad : List.of(countResponse().replace("\"doc_count\":1000", "\"doc_count\":1"),
                countResponse().replace("\"population\"", "\"wrong_population\""))) {
            reply.set(bad);
            assertThatThrownBy(() -> client().executeCondition("PRESENT|ONESELF|HP:1"))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.code()).isEqualTo(UPSTREAM_INVALID_RESPONSE));
        }
    }
    @Test void keepsLargeCountsExactAndUnknownFieldsNull() {
        reply.set(OK.replace("\"value\":0", "\"value\":9007199254740993"));
        var snapshot = new SearchProbeController(client()).searchProbe().snapshot();
        assertThat(snapshot.participantCount()).isEqualTo(new java.math.BigInteger("9007199254740993"));
        assertThat(snapshot.totalParticipantCount()).isEqualTo(snapshot.participantCount());
        assertThat(snapshot.indexRelease()).isNull();
        assertThat(snapshot.unavailableFields()).contains("studyCount", "datasetCount", "indexRelease");
    }
    @Test void rejectsMalformedKeywordBeforeSendingRequest() {
        for (String keyword : List.of("", "diabetes", "MONDO:1|ABSENT", "PRESENT||MONDO:1", "a".repeat(501))) {
            assertThatThrownBy(() -> client().executeCondition(keyword)).isInstanceOfSatisfying(
                    ApiException.class, error -> assertThat(error.code()).isEqualTo(BAD_USER_INPUT));
        }
        assertThat(path.get()).isNull();
    }
    @Test void rejectsMissingOrMalformedAggregations() {
        for (String bad : List.of(OK, countResponse().replace("\"doc_count\":12", "\"doc_count\":-1"),
                countResponse().replace("\"buckets\":[", "\"wrong\":["))) {
            reply.set(bad);
            assertThatThrownBy(() -> client().executeCondition("PRESENT|ONESELF|MONDO:0005148"))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.code()).isEqualTo(UPSTREAM_INVALID_RESPONSE));
        }
    }
    @Test void acceptsEmptyBuckets() {
        reply.set(countResponse().replace("{\"key\":\"PRESENT|ONESELF|MONDO:0005252\",\"doc_count\":12}", ""));
        assertThat(new SearchProbeController(client()).searchConditions("PRESENT|ONESELF|MONDO:0005148", null).buckets()).isEmpty();
    }
    @Test void preservesActualSuccessfulHttpStatus() {
        status.set(201);
        assertThat(client().executeProbe().httpStatus()).isEqualTo(201);
    }
    @Test void disabledMeansNoNetworkCall() {
        var disabled = new ElasticsearchMetadataSearch(properties(false, Duration.ofSeconds(2)));
        assertThatThrownBy(disabled::executeProbe).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(UPSTREAM_UNAVAILABLE));
        assertThat(path.get()).isNull();
    }
    @Test void rejectsBackendHttpErrorsWithoutExposingBody() {
        for (int code : List.of(302, 307, 401, 403, 404, 429, 500, 503)) {
            status.set(code); reply.set("SECRET BACKEND DETAILS");
            assertThatThrownBy(() -> client().executeProbe()).isInstanceOf(ApiException.class)
                    .hasMessageContaining("HTTP " + code).hasMessageNotContaining("SECRET");
        }
    }
    @Test void rejectsEmptyMalformedAndWrongShapeResponses() {
        for (String bad : List.of("", "not-json", "null", "[]", "{}", "{\"hits\":{}}")) {
            reply.set(bad); failsWith(UPSTREAM_INVALID_RESPONSE);
        }
    }
    @Test void rejectsPartialResults() {
        reply.set(OK.replace("\"failed\":0", "\"failed\":1"));
        failsWith(UPSTREAM_UNAVAILABLE);
    }
    @Test void rejectsBackendTimeoutEvenOnHttp200() {
        reply.set(OK.replace("\"timed_out\":false", "\"timed_out\":true"));
        failsWith(UPSTREAM_TIMEOUT);
    }
    @Test void rejectsApproximateCounts() {
        reply.set(OK.replace("\"eq\"", "\"gte\""));
        failsWith(UPSTREAM_INVALID_RESPONSE);
    }
    @Test void rejectsOversizedResponses() {
        reply.set(" ".repeat(1_048_577)); failsWith(UPSTREAM_INVALID_RESPONSE);
    }
    @Test void classifiesReadTimeout() {
        delayMillis.set(250);
        var search = new ElasticsearchMetadataSearch(properties(true, Duration.ofMillis(30)));
        assertThatThrownBy(search::executeProbe).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.code()).isEqualTo(UPSTREAM_TIMEOUT));
    }
    @Test void classifiesUnreachableService() {
        var search = client(); server.stop(0);
        assertThatThrownBy(search::executeProbe).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.code()).isEqualTo(UPSTREAM_UNAVAILABLE));
    }
}
