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

import org.biodatacatalyst.middleware.harmonized.HarmonizedVariableLibrary;
import org.biodatacatalyst.middleware.shared.ApiException;
import org.biodatacatalyst.middleware.terms.TermService;

public final class MonarchContractChecks {

    /** One upstream search hit, the building block for every response body below. */
    private static final String ITEM = """
        {"id":"HP:0000822","name":"Hypertension","category":"biolink:PhenotypicFeature",
         "description":null,"synonym":["High blood pressure"]}
        """;

    public static void main() throws Exception {
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

        // Both upstream routes are served by the same handler; the checks assert on the path and
        // query string the client actually built.
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
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
        };
        server.createContext("/v3/api/search", handler);
        server.createContext("/v3/api/autocomplete", handler);
        server.start();

        try {
            // Timeouts are short so the timeout check does not slow the build down.
            var props = new MonarchProperties(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/api"),
                    Duration.ofMillis(500), Duration.ofMillis(500));
            var client = new MonarchClient(props, new MonarchResponseMapper());
            var service = new TermService(client, HarmonizedVariableLibrary.disabled());

            // ---- successful mapping -------------------------------------------------------
            var result = client.search("Hypertension", 20, 0);
            require(result.total() == 2 && result.items().size() == 1, "success mapping");
            require(result.items().getFirst().description() == null, "null description");
            require(result.items().getFirst().synonyms().getFirst().equals("High blood pressure"), "synonym mapping");
            require(result.items().getFirst().displayLabel().equals("Hypertension (HP:0000822)"), "display label mapping");
            // Surrounding spaces must not survive into the upstream request.
            require(service.terms(" Hypertension ", 20, 0).hasMore(), "hasMore across the merged set");
            require(request.get().getRawQuery().equals("q=Hypertension&limit=20&offset=0"), "request parameters");

            // Several candidates are all returned; none is chosen on the user's behalf.
            body.set(page(ITEM + "," + ITEM.replace("HP:0000822", "MONDO:0005044"), 2, 20, 0));
            require(client.search("Hypertension", 20, 0).items().size() == 2, "multiple candidates");

            // ---- URL encoding -------------------------------------------------------------
            // Every character here would break the query string if it were not escaped.
            String text = "A+B & C# / {test} caf\u00e9";
            service.terms(text, 20, 0);
            String encoded = request.get().getRawQuery().split("&")[0].substring(2);
            require(URLDecoder.decode(encoded, StandardCharsets.UTF_8).equals(text), "reserved characters and Unicode");

            // ---- empty results are a success, not a failure -------------------------------
            body.set(page("", 0, 20, 0));
            result = client.search("no match", 20, 0);
            require(result.items().isEmpty() && result.total() == 0, "empty result");
            require(!service.terms("no match", 20, 0).hasMore(), "empty result reports no further page");
            body.set(page("", 2, 20, 20));
            require(!service.terms("past last page", 20, 20).hasMore(), "offset beyond total");

            // ---- null versus empty is preserved -------------------------------------------
            body.set(page(ITEM.replace("[\"High blood pressure\"]", "null"), 1, 20, 0));
            require(client.search("x", 20, 0).items().getFirst().synonyms() == null, "null synonyms");
            body.set(page(ITEM.replace("[\"High blood pressure\"]", "[]"), 1, 20, 0));
            require(client.search("x", 20, 0).items().getFirst().synonyms().isEmpty(), "empty synonyms");

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

            // ---- autocomplete: a second upstream route on the same client -----------------
            status.set(200);
            body.set(page(ITEM, 1, 20, 0));
            var suggestions = client.autocomplete("Hyper");
            require(suggestions.items().size() == 1, "autocomplete mapping");
            require(suggestions.items().getFirst().displayLabel().equals("Hypertension (HP:0000822)"),
                    "autocomplete display label");
            require(request.get().getPath().endsWith("/autocomplete"), "autocomplete route");
            // Only q: the upstream route accepts nothing else, so nothing else may be sent.
            require(request.get().getRawQuery().equals("q=Hyper"), "autocomplete sends only q");

            require(suggestions.items().getFirst().description() == null, "null autocomplete description");
            body.set(page(ITEM.replace("\"description\":null", "\"description\":\"A description\""), 1, 20, 0));
            require("A description".equals(client.autocomplete("Hyper").items().getFirst().description()),
                    "description survives mapping and BDC annotation");
            for (String bad : new String[]{
                    page(ITEM.replace("\"description\":null", "\"description\":{}"), 1, 20, 0),
                    page(ITEM, -1, 20, 0), page(ITEM, 0, 20, 0),
                    page(ITEM, 1, 20, 0).replace("\"total\":1", "\"total\":\"bad\"")}) {
                body.set(bad);
                expect("UPSTREAM_INVALID_RESPONSE", () -> service.autocomplete("Hyper"));
            }

            // A repeated identifier is dropped, not treated as a broken response: it is a cosmetic
            // upstream quirk we can handle losslessly, and failing would be a total outage. The
            // reported total still describes the page the upstream sent, so paging stays honest.
            body.set(page(ITEM + "," + ITEM, 2, 20, 0));
            var deduped = client.autocomplete("Hyper");
            require(deduped.items().size() == 1, "autocomplete drops a repeated identifier");
            require(deduped.total() == 2, "autocomplete total still reflects the upstream page");

            require(client.search("Hyper", 20, 0).items().size() == 1, "search drops a repeated identifier");
            require(!service.terms("Hyper", 20, 0).hasMore(), "a dropped repeat does not invent a further page");

            // total is optional on this route; it falls back to the number of suggestions returned.
            body.set("{\"items\":[" + ITEM + "]}");
            require(client.autocomplete("Hyper").total() == 1, "autocomplete total falls back");

            // An envelope without items is still rejected, and a suggestion without an id with it.
            for (String bad : new String[]{"{}", "{\"items\":{}}",
                    "{\"items\":[" + ITEM.replace("\"HP:0000822\"", "null") + "]}"}) {
                body.set(bad);
                expect("UPSTREAM_INVALID_RESPONSE", () -> service.autocomplete("Hyper"));
            }
            // Validation applies to both routes alike.
            expect("BAD_USER_INPUT", () -> service.autocomplete(" "));

            // ---- upstream failure modes, which must run last ------------------------------
            // The refused-connection check stops the server, so nothing below it can reach one.
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
                    + "malformed bodies, HTTP errors, timeout, connection failure and autocomplete");
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
