package dev.plainround.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A quote provider you control. It is deliberately not a Spring application: a plain {@link
 * HttpServer} with a mode you flip at run time and its own counters (calls received, calls in
 * flight, the most in flight at once), so every claim about retries and concurrency can be checked
 * against a count the client did not make.
 *
 * <ul>
 *   <li>{@code GET /quotes/{sku}} answers according to the current {@link ProviderMode}.
 *   <li>{@code PUT /admin/mode?mode=slow&delayMs=5000} switches the mode; {@code &failNext=2} makes
 *       the next two calls answer 503 whatever the mode is.
 *   <li>{@code GET /admin/stats} returns the counters as JSON; {@code POST /admin/reset} zeroes
 *       them.
 *   <li>{@code GET /metrics} returns the counters in the Prometheus text format; {@code GET
 *       /health} answers ok.
 * </ul>
 */
public final class FakeProvider implements AutoCloseable {

    /**
     * How long {@link ProviderMode#HANG} stays silent unless {@link #hangMillis(long)} says
     * otherwise.
     */
    public static final long HANG_MILLIS = 10_000;

    private static final long DEFAULT_SLOW_MILLIS = 5_000;

    private final AtomicInteger callsReceived = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicLong slowMillis = new AtomicLong(DEFAULT_SLOW_MILLIS);
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicLong hangMillis = new AtomicLong(HANG_MILLIS);
    private volatile ProviderMode mode = ProviderMode.NORMAL;
    private final HttpServer server;

    /**
     * Creates the provider; call {@link #start()} to accept requests.
     *
     * @param port the port to listen on, 0 for any free port
     * @throws IOException if the port cannot be bound
     */
    public FakeProvider(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/quotes/", this::quote);
        server.createContext("/admin/mode", this::setMode);
        server.createContext(
                "/admin/stats", exchange -> reply(exchange, 200, "application/json", statsJson()));
        server.createContext("/admin/reset", this::reset);
        server.createContext(
                "/metrics",
                exchange -> reply(exchange, 200, "text/plain; version=0.0.4", metricsText()));
        server.createContext("/health", exchange -> reply(exchange, 200, "text/plain", "ok"));
    }

    /** Starts accepting requests. */
    public void start() {
        server.start();
    }

    /**
     * Returns the port actually bound.
     *
     * @return the port
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Switches the mode directly, for tests that run the provider in the same JVM.
     *
     * @param newMode the mode to answer with from now on
     */
    public void mode(ProviderMode newMode) {
        mode = newMode;
    }

    /**
     * Sets how long {@link ProviderMode#HANG} stays silent, so tests do not leave ten-second calls
     * behind.
     *
     * @param millis the silence in milliseconds
     */
    public void hangMillis(long millis) {
        hangMillis.set(millis);
    }

    /**
     * Makes the next calls answer 503 whatever the mode is, then return to the mode. This is how a
     * request that fails twice and succeeds on the third attempt is produced.
     *
     * @param calls how many calls to fail
     */
    public void failNext(int calls) {
        failNext.set(calls);
    }

    /**
     * Sets how long {@link ProviderMode#SLOW} waits before answering.
     *
     * @param millis the delay in milliseconds
     */
    public void slowMillis(long millis) {
        slowMillis.set(millis);
    }

    /**
     * Returns how many quote requests have arrived since the last reset.
     *
     * @return the count
     */
    public int callsReceived() {
        return callsReceived.get();
    }

    /**
     * Returns how many quote requests are being served right now.
     *
     * @return the count
     */
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * Returns the most quote requests that were ever being served at the same moment since the last
     * reset.
     *
     * @return the maximum
     */
    public int maxInFlight() {
        return maxInFlight.get();
    }

    /** Zeroes the counters. */
    public void resetCounters() {
        callsReceived.set(0);
        maxInFlight.set(inFlight.get());
    }

    @Override
    public void close() {
        server.stop(1);
    }

    private void quote(HttpExchange exchange) throws IOException {
        callsReceived.incrementAndGet();
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            if (failNext.getAndUpdate(remaining -> Math.max(remaining - 1, 0)) > 0) {
                reply(exchange, 503, "text/plain", "unavailable");
                return;
            }
            switch (mode) {
                case FAIL503 -> reply(exchange, 503, "text/plain", "unavailable");
                case HANG -> {
                    sleep(hangMillis.get());
                    reply(exchange, 200, "application/json", "{}");
                }
                case SLOW -> {
                    sleep(slowMillis.get());
                    reply(exchange, 200, "application/json", quoteJson(exchange.getRequestURI()));
                }
                default ->
                        reply(
                                exchange,
                                200,
                                "application/json",
                                quoteJson(exchange.getRequestURI()));
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void setMode(HttpExchange exchange) throws IOException {
        if (!"PUT".equals(exchange.getRequestMethod())) {
            reply(exchange, 405, "text/plain", "use PUT");
            return;
        }
        try {
            String query = exchange.getRequestURI().getQuery();
            mode = ProviderMode.parse(queryValue(query, "mode"));
            String delay = queryValue(query, "delayMs");
            if (delay != null) {
                slowMillis.set(Long.parseLong(delay));
            }
            String failures = queryValue(query, "failNext");
            failNext.set(failures == null ? 0 : Integer.parseInt(failures));
            JsonLog.info("mode set to " + mode + ", slow delay " + slowMillis.get() + " ms");
            reply(exchange, 200, "application/json", statsJson());
        } catch (IllegalArgumentException exception) {
            reply(exchange, 400, "text/plain", exception.getMessage());
        }
    }

    private void reset(HttpExchange exchange) throws IOException {
        resetCounters();
        reply(exchange, 200, "application/json", statsJson());
    }

    private String statsJson() {
        return "{\"mode\":\""
                + mode.name().toLowerCase(java.util.Locale.ROOT)
                + "\",\"slowDelayMs\":"
                + slowMillis.get()
                + ",\"callsReceived\":"
                + callsReceived.get()
                + ",\"inFlight\":"
                + inFlight.get()
                + ",\"maxInFlight\":"
                + maxInFlight.get()
                + "}";
    }

    private String metricsText() {
        return "# TYPE provider_calls_received_total counter\nprovider_calls_received_total "
                + callsReceived.get()
                + "\n# TYPE provider_in_flight gauge\nprovider_in_flight "
                + inFlight.get()
                + "\n# TYPE provider_in_flight_max gauge\nprovider_in_flight_max "
                + maxInFlight.get()
                + "\n";
    }

    private static String quoteJson(URI uri) {
        String path = uri.getPath();
        String sku = path.substring(path.lastIndexOf('/') + 1);
        BigDecimal price =
                BigDecimal.valueOf(1000 + Math.floorMod(sku.hashCode(), 9000))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
        return "{\"price\":" + price.toPlainString() + "}";
    }

    private static String queryValue(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name) && parts.length == 2) {
                return parts[1];
            }
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException clientGone) {
            // The client gave up (its read timeout fired) before this answer; there is nobody to
            // tell.
            JsonLog.info(
                    "client closed the connection before the answer: " + clientGone.getMessage());
        } finally {
            exchange.close();
        }
    }
}
