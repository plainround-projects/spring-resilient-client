package dev.plainround.loadgen;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line: {@code java -jar loadgen.jar --target http://host/price/{sku} --skus 10 --rps 100
 * --seconds 30 [--side http://host/skus/sku-1 --side-rps 10]}. Prints one JSON report per target on
 * its own line.
 */
public final class LoadMain {

    private LoadMain() {}

    /**
     * Runs the load and prints the reports.
     *
     * @param args the options described above
     * @throws InterruptedException if the run is interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        Map<String, String> options = parse(args);
        int rate = Integer.parseInt(options.getOrDefault("rps", "100"));
        int seconds = Integer.parseInt(options.getOrDefault("seconds", "30"));
        int skus = Integer.parseInt(options.getOrDefault("skus", "10"));
        List<Thread> runs = new ArrayList<>();
        List<Report> reports = java.util.Collections.synchronizedList(new ArrayList<>());
        String target = require(options, "target");
        runs.add(start(() -> reports.add(run("price", target, skus, rate, seconds))));
        if (options.containsKey("side")) {
            int sideRate = Integer.parseInt(options.getOrDefault("side-rps", "10"));
            runs.add(
                    start(
                            () ->
                                    reports.add(
                                            run(
                                                    "side",
                                                    options.get("side"),
                                                    1,
                                                    sideRate,
                                                    seconds))));
        }
        for (Thread run : runs) {
            run.join();
        }
        reports.stream().map(Report::toJson).forEach(System.out::println);
    }

    private static Report run(String name, String template, int skus, int rate, int seconds) {
        try {
            return new LoadRunner()
                    .run(
                            name,
                            number -> URI.create(template.replace("{sku}", "sku-" + number % skus)),
                            rate,
                            seconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Report.of(name, new long[0], 0, 0);
        }
    }

    private static Thread start(Runnable work) {
        return Thread.ofPlatform().start(work);
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index + 1 < args.length; index += 2) {
            options.put(args[index].replaceFirst("^--", ""), args[index + 1]);
        }
        return options;
    }
}
