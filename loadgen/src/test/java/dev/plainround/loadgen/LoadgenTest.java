package dev.plainround.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class LoadgenTest {

    @Test
    void percentilesUseTheNearestRank() {
        long[] oneToHundredMillis =
                LongStream.rangeClosed(1, 100).map(millis -> millis * 1_000_000).toArray();

        Report report = Report.of("t", oneToHundredMillis, 0, 0);

        assertThat(report.p50Millis()).isEqualTo(50.0);
        assertThat(report.p99Millis()).isEqualTo(99.0);
    }

    @Test
    void anEmptyRunReportsZeros() {
        Report report = Report.of("t", new long[0], 0, 0);

        assertThat(report.p99Millis()).isZero();
        assertThat(report.errorRate()).isZero();
    }

    @Test
    void theJsonLineCarriesEveryNumber() {
        Report report = new Report("price", 10, 2, 3, 4.0, 12.5);

        assertThat(report.toJson())
                .isEqualTo(
                        "{\"name\":\"price\",\"requests\":10,\"errors\":2,\"errorRate\":0.2000,\"stale\":3,\"p50Ms\":4.0,\"p99Ms\":12.5}");
    }

    @Test
    void optionsParseInPairs() {
        assertThat(LoadMain.parse(new String[] {"--rps", "50", "--target", "http://x/{sku}"}))
                .containsEntry("rps", "50")
                .containsEntry("target", "http://x/{sku}");
    }

    @Test
    void theRunnerHoldsTheRateAndCountsErrorsAndStaleAnswers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ok", exchange -> reply(exchange, 200, "{\"stale\":true}"));
        server.createContext("/bad", exchange -> reply(exchange, 503, "no"));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();

            Report ok = new LoadRunner().run("ok", number -> URI.create(base + "/ok"), 20, 1);
            Report bad = new LoadRunner().run("bad", number -> URI.create(base + "/bad"), 10, 1);

            assertThat(ok.requests()).isEqualTo(20);
            assertThat(ok.errors()).isZero();
            assertThat(ok.stale()).isEqualTo(20);
            assertThat(bad.errors()).isEqualTo(10);
            assertThat(bad.errorRate()).isEqualTo(1.0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mainRunsThePriceTargetAndTheSideTargetAndPrintsOneJsonLineEach() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> reply(exchange, 200, "{}"));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
            String base = "http://localhost:" + server.getAddress().getPort();

            LoadMain.main(
                    new String[] {
                        "--target",
                        base + "/price/{sku}",
                        "--skus",
                        "3",
                        "--rps",
                        "10",
                        "--seconds",
                        "1",
                        "--side",
                        base + "/skus/sku-1",
                        "--side-rps",
                        "5"
                    });
        } finally {
            System.setOut(original);
            server.stop(0);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8).lines().toList())
                .anySatisfy(
                        line -> assertThat(line).contains("\"name\":\"price\"", "\"requests\":10"))
                .anySatisfy(
                        line -> assertThat(line).contains("\"name\":\"side\"", "\"requests\":5"));
    }

    @Test
    void anUnreachableTargetCountsAsErrors() throws Exception {
        Report report =
                new LoadRunner().run("down", number -> URI.create("http://localhost:1/x"), 5, 1);

        assertThat(report.errors()).isEqualTo(5);
    }

    @Test
    void aMissingTargetIsRejected() {
        assertThatThrownBy(() -> LoadMain.main(new String[] {"--rps", "1"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
