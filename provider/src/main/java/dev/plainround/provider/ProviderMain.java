package dev.plainround.provider;

import java.io.IOException;

/**
 * Starts the fake provider on {@code PORT} (default 8081) and stops it when the process is told to.
 */
public final class ProviderMain {

    private ProviderMain() {}

    /**
     * Runs the provider until the JVM receives a termination signal.
     *
     * @param args ignored; configuration comes from the environment
     * @throws IOException if the port cannot be bound
     */
    public static void main(String[] args) throws IOException {
        FakeProvider provider =
                start(Integer.parseInt(System.getenv().getOrDefault("PORT", "8081")));
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    JsonLog.info("shutting down");
                                    provider.close();
                                }));
    }

    /**
     * Creates and starts a provider.
     *
     * @param port the port to listen on, 0 for any free port
     * @return the running provider
     * @throws IOException if the port cannot be bound
     */
    static FakeProvider start(int port) throws IOException {
        FakeProvider provider = new FakeProvider(port);
        provider.start();
        JsonLog.info("fake provider listening on port " + provider.port());
        return provider;
    }
}
