package org.biodatacatalyst.middleware.monarch;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.biodatacatalyst.middleware.shared.ApiException;
import org.biodatacatalyst.middleware.terms.TermService;

public final class MonarchContractChecks {

    /** One upstream search hit, the building block for every response body below. */
    private static final String ITEM = """
        {"id":"HP:0000822","name":"Hypertension","category":"biolink:PhenotypicFeature",
         "description":null,"synonym":["High blood pressure"]}
        """;

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        // Knobs the checks turn to drive the client down each path.
        var body = new AtomicReference<>(page(ITEM, 2, 20, 0));
        var status = new AtomicInteger(200);
        var delay = new AtomicInteger();
        var count = new AtomicInteger();
        var request = new AtomicReference<URI>();

        server.createContext("/v3/api/search", exchange -> {
            count.incrementAndGet();
            request.set(exchange.getRequestURI());
            try {
                if (delay.get() > 0) {
                    Thread.sleep(delay.get());
                }
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            // Timeouts are short so the timeout check does not slow the build down.
            var props = new MonarchProperties(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/api"),
                    Duration.ofMillis(500), Duration.ofMillis(500));
            var service = new TermService(new MonarchClient(props, new MonarchResponseMapper()));

            // ---- successful mapping -------------------------------------------------------
            var result = service.terms(" Hypertension ", 20, 0);
            require(result.total() == 2 && result.items().size() == 1 && result.hasMore(), "success pagination");
            require(result.items().get(0).description() == null, "null description");
            require(result.items().get(0).synonyms().get(0).equals("High blood pressure"), "synonym mapping");
            require(result.items().get(0).displayLabel().equals("Hypertension (HP:0000822)"), "display label mapping");
            // The surrounding spaces above must not survive into the upstream request.
            require(request.get().getRawQuery().equals("q=Hypertension&limit=20&offset=0"), "request parameters");

            // Several candidates are all returned; none is chosen on the user's behalf.
            body.set(page(ITEM + "," + ITEM.replace("HP:0000822", "MONDO:0005044"), 2, 20, 0));
            require(service.terms("Hypertension", 20, 0).items().size() == 2, "multiple candidates");

            // ---- URL encoding -------------------------------------------------------------
            // Every character here would break the query string if it were not escaped.
            String text = "A+B & C# / {test} caf\u00e9";
            service.terms(text, 20, 0);
            String encoded = request.get().getRawQuery().split("&")[0].substring(2);
            require(URLDecoder.decode(encoded, StandardCharsets.UTF_8).equals(text), "reserved characters and Unicode");

            // ---- empty results are a success, not a failure -------------------------------
            body.set(page("", 0, 20, 0));
            result = service.terms("no match", 20, 0);
            require(result.items().isEmpty() && result.total() == 0 && !result.hasMore(), "empty result");
            body.set(page("", 2, 20, 20));
            require(!service.terms("past last page", 20, 20).hasMore(), "offset beyond total");

            // ---- null versus empty is preserved -------------------------------------------
            body.set(page(ITEM.replace("[\"High blood pressure\"]", "null"), 1, 20, 0));
            require(service.terms("x", 20, 0).items().get(0).synonyms() == null, "null synonyms");
            body.set(page(ITEM.replace("[\"High blood pressure\"]", "[]"), 1, 20, 0));
            require(service.terms("x", 20, 0).items().get(0).synonyms().isEmpty(), "empty synonyms");

            // ---- input validation happens before any upstream call ------------------------
            int before = count.get();
            for (String q : new String[]{" ", "x".repeat(201)}) {
                expect("BAD_USER_INPUT", () -> service.terms(q, 20, 0));
            }
            for (int limit : new int[]{0, 101, 500}) {
                expect("BAD_USER_INPUT", () -> service.terms("x", limit, 0));
            }
            for (int offset : new int[]{-1, 10001}) {
                expect("BAD_USER_INPUT", () -> service.terms("x", 20, offset));
            }
            require(count.get() == before, "invalid input must not call Monarch");

            // ---- a changed upstream contract must not look like "no matches" --------------
            for (String bad : new String[]{"not-json", "null", "[]", "{}", "{\"items\":{}}",
                    page(ITEM.replace("\"HP:0000822\"", "null"), 1, 20, 0),
                    page(ITEM.replace("\"biolink:PhenotypicFeature\"", "[]"), 1, 20, 0),
                    page(ITEM.replace("[\"High blood pressure\"]", "[123]"), 1, 20, 0),
                    // Paging that contradicts the request that was sent.
                    page(ITEM, 1, 10, 0), page(ITEM, 0, 20, 0)}) {
                body.set(bad);
                expect("UPSTREAM_INVALID_RESPONSE", () -> service.terms("x", 20, 0));
            }

            // ---- upstream failure modes ---------------------------------------------------
            body.set(page("", 0, 20, 0));
            for (int code : new int[]{400, 404, 429, 500, 503}) {
                status.set(code);
                expect("UPSTREAM_UNAVAILABLE", () -> service.terms("x", 20, 0));
            }
            // A slow response must hit the read timeout and be reported as such.
            status.set(200);
            delay.set(1200);
            expect("UPSTREAM_TIMEOUT", () -> service.terms("x", 20, 0));
            // A refused connection is a different condition and gets a different code.
            delay.set(0);
            server.stop(0);
            expect("UPSTREAM_UNAVAILABLE", () -> service.terms("x", 20, 0));

            System.out.println("PASS: success, candidates, encoding, nulls, pagination, validation, "
                    + "malformed bodies, HTTP errors, timeout and connection failure");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** A Monarch {@code SearchResults} envelope wrapping the given raw item JSON. */
    private static String page(String items, int total, int limit, int offset) {
        return "{\"items\":[" + items + "],\"total\":" + total
                + ",\"limit\":" + limit + ",\"offset\":" + offset + "}";
    }

    /** Asserts that the action fails with exactly the given {@link ApiException.Code}. */
    private static void expect(String code, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected " + code);
        } catch (ApiException e) {
            require(e.code().name().equals(code), "Expected " + code + ", got " + e.code());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
