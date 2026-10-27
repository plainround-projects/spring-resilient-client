package dev.plainround.loadgen;

import java.util.Arrays;
import java.util.Locale;

/**
 * The outcome of one load run against one target.
 *
 * @param name a label for the target
 * @param requests how many requests were sent
 * @param errors how many answered something other than 2xx, or not at all
 * @param stale how many 2xx answers said {@code "stale":true}
 * @param p50Millis median latency in milliseconds
 * @param p99Millis 99th-percentile latency in milliseconds
 */
public record Report(
        String name, int requests, int errors, int stale, double p50Millis, double p99Millis) {

    /**
     * Builds a report from raw samples.
     *
     * @param name a label for the target
     * @param latenciesNanos one latency per request, in nanoseconds
     * @param errors how many requests failed
     * @param stale how many answers were stale
     * @return the report
     */
    public static Report of(String name, long[] latenciesNanos, int errors, int stale) {
        long[] sorted = latenciesNanos.clone();
        Arrays.sort(sorted);
        return new Report(
                name, sorted.length, errors, stale, percentile(sorted, 50), percentile(sorted, 99));
    }

    /**
     * Returns the share of requests that failed.
     *
     * @return a fraction between 0 and 1
     */
    public double errorRate() {
        return requests == 0 ? 0 : (double) errors / requests;
    }

    /**
     * Renders the report as one JSON object on one line.
     *
     * @return the JSON text
     */
    public String toJson() {
        return String.format(
                Locale.ROOT,
                "{\"name\":\"%s\",\"requests\":%d,\"errors\":%d,\"errorRate\":%.4f,\"stale\":%d,\"p50Ms\":%.1f,\"p99Ms\":%.1f}",
                name,
                requests,
                errors,
                errorRate(),
                stale,
                p50Millis,
                p99Millis);
    }

    private static double percentile(long[] sorted, int percent) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1] / 1_000_000.0;
    }
}
