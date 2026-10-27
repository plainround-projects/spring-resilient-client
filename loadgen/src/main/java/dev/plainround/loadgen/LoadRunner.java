package dev.plainround.loadgen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.LongStream;

/**
 * Sends requests at a fixed rate whether or not the earlier ones have come back (an open loop), so
 * a slow target shows up as latency instead of quietly lowering the load.
 */
public final class LoadRunner {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * Runs one target for a fixed time.
     *
     * @param name a label for the report
     * @param urlForRequest maps the request number (0, 1, 2, ...) to the URL to call
     * @param requestsPerSecond the rate to hold
     * @param seconds how long to send
     * @return the report, produced after every answer (or timeout) is in
     * @throws InterruptedException if the run is interrupted
     */
    public Report run(
            String name, IntFunction<URI> urlForRequest, int requestsPerSecond, int seconds)
            throws InterruptedException {
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        int total = requestsPerSecond * seconds;
        long startNanos = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int number = 0; number < total; number++) {
                sleepUntil(startNanos + number * (1_000_000_000L / requestsPerSecond));
                URI uri = urlForRequest.apply(number);
                pool.submit(() -> send(uri, latencies, errors, stale));
            }
            pool.shutdown();
            pool.awaitTermination(REQUEST_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        }
        return Report.of(name, toArray(latencies), errors.get(), stale.get());
    }

    private void send(
            URI uri,
            ConcurrentLinkedQueue<Long> latencies,
            AtomicInteger errors,
            AtomicInteger stale) {
        long started = System.nanoTime();
        boolean failed = false;
        boolean wasStale = false;
        try {
            HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).build(),
                            HttpResponse.BodyHandlers.ofString());
            failed = response.statusCode() / 100 != 2;
            wasStale = !failed && response.body().contains("\"stale\":true");
        } catch (IOException exception) {
            failed = true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failed = true;
        }
        latencies.add(System.nanoTime() - started);
        errors.addAndGet(failed ? 1 : 0);
        stale.addAndGet(wasStale ? 1 : 0);
    }

    private static void sleepUntil(long deadlineNanos) throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static long[] toArray(ConcurrentLinkedQueue<Long> latencies) {
        return List.copyOf(latencies).stream().flatMapToLong(LongStream::of).toArray();
    }
}
