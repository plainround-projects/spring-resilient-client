# Spring Boot Test (test slices, MockMvc, full-context tests)

## What it is

Spring Boot Test is the set of annotations that start a Spring application context inside a test. You choose how much to start:

- **No Spring** (plain JUnit): fastest; tests one class.
- **A slice**, such as `@WebMvcTest`: starts only the web layer (controllers, the error advice, JSON conversion) and replaces the rest with mocks.
- **The full context**, `@SpringBootTest`: starts the whole application, optionally on a real port.

**MockMvc** is a tool that sends a fake HTTP request straight into the controllers, without a network, and lets you assert on the answer.

## Why this repository uses it

The lesson says to prefer slice tests and to prove each status code with a test. A slice starts faster than the full application and fails for web reasons only. A few full-context tests (`*IT`) then prove the pieces fit together.

## Where it appears

| Path | Kind |
| --- | --- |
| `interfaces/web/PriceControllerTest.java` | `@WebMvcTest` with MockMvc |
| `infrastructure/ProviderPropertiesTest.java` | `ApplicationContextRunner` |
| `infrastructure/ResilienceAnnotationsTest.java` | a tiny context built by hand (`AnnotationConfigApplicationContext`) |
| `PriceServiceBehaviorIT.java` | `@SpringBootTest` on a random port with a real provider in the same JVM |

## How it works underneath

1. The Spring extension for JUnit builds an application context for the test class and **caches** it: the next class with the same configuration reuses it, which saves minutes.
2. `@WebMvcTest(X.class)` finds the application class, but loads only web beans: controllers, advice, filters, JSON converters. Services behind the controller are missing, so the test declares them with `@MockitoBean`.
3. `MockMvc` calls the DispatcherServlet directly in the same thread: `mockMvc.perform(post("/books").content(...))` returns a result you check with `status()`, `jsonPath(...)`, `content()`.
4. `@SpringBootTest(webEnvironment = RANDOM_PORT)` starts the real embedded web server on a free port; the test then uses an HTTP client against `localhost:<port>`. The port is available as `${local.server.port}`.
5. `ApplicationContextRunner` builds a context from a few classes and lets you assert that startup fails or succeeds with given properties.
6. The test classpath adds a `config/application.properties` that is merged over the main one (see `properties-files.md`).

## Walkthrough of this repository's use

```java
@WebMvcTest({PriceController.class, CatalogController.class})
class PriceControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PriceService prices;
    @MockitoBean private CatalogService catalog;
    @MockitoBean private Tracer tracer;
```

`@WebMvcTest({PriceController.class, CatalogController.class})` loads two controllers and the advice. Every scripted failure from the use case becomes an HTTP assertion: 200 fresh, 200 stale, 503 with `Retry-After`, 504, 400 for a bad SKU.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PriceServiceBehaviorIT.TestClock.class)
class PriceServiceBehaviorIT {
```

The behavior test starts the whole service on a random port and injects a **mutable clock** (a test-only bean marked `@Primary`) so a 60 second expiry can be tested without waiting. `@DynamicPropertySource` is used to point the service at the fake provider's port, which is only known at run time.

```java
void twentyConcurrentCallsAgainstALimitOfThreeNeverShowMoreThanThreeInFlight()
        throws Exception {
    try (AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext(Config.class)) {
        Slow slow = context.getBean(Slow.class);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int call = 0; call < 20; call++) {
                calls.add(
                        pool.submit(
                                () -> {
                                    slow.blockingCall();
                                    return null;
                                }));
            }
            for (Future<?> call : calls) {
                call.get();
            }
        }

        assertThat(slow.maxInFlight()).isEqualTo(3);
    }
}
```

A tiny context made by hand with `@EnableResilientMethods` tests the resilience annotations alone, with no web server.

**Every test class that starts a Spring context or a slice (`@WebMvcTest`, `@DataJpaTest`, `@SpringBootTest`, `ApplicationContextRunner`, `AnnotationConfigApplicationContext`), with its tests:**

- `price-service/src/test/java/dev/plainround/price/PriceServiceBehaviorIT.java`:
  - `healthyProviderGivesAFreshPriceWithOneCall`
  - `retriesAtMostThreeCallsTotalOn503`
  - `doesNotRetryOnATimeoutAndAnswers504InAboutTheReadTimeout`
  - `aSlowProviderBeyondTheTimeoutIsNotRetriedEither`
  - `aRecentlyPricedSkuIsServedStaleWhenTheProviderIsDownAndANeverPricedOneIs503`
  - `aQuoteOlderThanSixtySecondsIsNotServed`
  - `staleServingAlsoCoversATimeout`
  - `inFlightProviderCallsNeverExceedTheLimitWhileOtherEndpointsKeepAnswering`
  - `metricsAndTraceIdsAreExposed`
  - `healthIsUpWithLivenessAndReadinessProbes`
- `price-service/src/test/java/dev/plainround/price/infrastructure/ProviderPropertiesTest.java`:
  - `defaultsAreOneSecondToConnectAndTwoToRead`
  - `aZeroOrNegativeTimeoutStopsTheApplicationAtStartup`
  - `aMissingBaseUrlOrAnAbsurdCacheBoundStopsTheApplication`
- `price-service/src/test/java/dev/plainround/price/infrastructure/ResilienceAnnotationsTest.java`:
  - `twentyConcurrentCallsAgainstALimitOfThreeNeverShowMoreThanThreeInFlight`
  - `theRejectPolicyRefusesTheExcessAtOnceInsteadOfQueueing`
  - `retryableStopsAfterTheConfiguredNumberOfRetriesAndOnlyForTheNamedException`
- `price-service/src/test/java/dev/plainround/price/interfaces/web/PriceControllerTest.java`:
  - `aFreshPriceIs200WithStaleFalse`
  - `aStalePriceIs200WithTheLabelAndTheAge`
  - `aNeverPricedSkuWithAFailingProviderIs503WithRetryAfter`
  - `aRefusedCallIsAlsoA503`
  - `aNeverPricedSkuWhoseCallTimedOutIs504`
  - `aSkuWithOddCharactersIs400NamingTheParameter`
  - `theCatalogListsProductsAndNamesAMissingOne`
  - `anUnexpectedErrorIs500WithoutLeakingTheMessage`

## Try it yourself

1. `./mvnw test -Dtest=PriceControllerTest -pl price-service` runs a slice test.
2. `./mvnw verify -pl provider,price-service` runs the full-context test with its real provider.
3. Read `target/surefire-reports/` (or `failsafe-reports/`) for the first failing assertion.
4. Search a test for `MockMvc` and compare it with the `curl` command in the README for the same call.

## Mistakes people make

- Using `@SpringBootTest` for everything: slow, and failures are far from the cause.
- Forgetting that a slice does not contain your service beans: the missing `@MockitoBean` shows as "No qualifying bean".
- Putting `@Transactional` on a test of transaction behavior: the test's own transaction changes the result.
- Test settings in a file with the same path as the main one: it hides the main file. Use `config/`.

## Check yourself

1. What does `@WebMvcTest` load? *Only the web layer.*
2. What replaces a service inside a slice? *A `@MockitoBean`.*
3. What does MockMvc avoid? *The network: it calls the dispatcher directly.*
4. Why is a context cached between test classes? *Starting one is slow.*
5. Which annotation gives a test the real server's port? *`@SpringBootTest(webEnvironment = RANDOM_PORT)`, then `${local.server.port}`.*

## Further reading

- Spring Boot 4.1.2, "Testing Spring Boot Applications": https://docs.spring.io/spring-boot/4.1.2/reference/testing/spring-boot-applications.html
- Spring Framework 7.0.10, MockMvc: https://docs.spring.io/spring-framework/reference/7.0.10/testing/mockmvc.html
