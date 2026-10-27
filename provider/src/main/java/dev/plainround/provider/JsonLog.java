package dev.plainround.provider;

import java.time.Instant;

/** One JSON object per line on standard output: level, message and time, nothing else. */
final class JsonLog {

    private JsonLog() {}

    static void info(String message) {
        write("INFO", message);
    }

    static void error(String message) {
        write("ERROR", message);
    }

    private static void write(String level, String message) {
        System.out.println(
                "{\"@timestamp\":\""
                        + Instant.now()
                        + "\",\"level\":\""
                        + level
                        + "\",\"service\":\"provider\""
                        + ",\"message\":\""
                        + escape(message)
                        + "\"}");
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
