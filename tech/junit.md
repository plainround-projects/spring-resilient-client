# JUnit (Jupiter)

## What it is

JUnit is the standard framework for writing automated tests in Java. A **test** is a method that runs some code and checks the result; if a check fails, the test fails. JUnit 5 (also called Jupiter) finds the test methods, runs them, and reports the results. Spring Boot 4.1.2 manages JUnit 6.0.3. JUnit 6 keeps the JUnit 5 programming model (the "Jupiter" API), so the annotations used here (`@Test`, `@ParameterizedTest`, `@BeforeEach`, `@AfterEach`) are the ones taught as "JUnit 5".

## Why this repository uses it

The lesson's "Done when" lines are statements a test can prove. Test names are written as sentences that describe behavior (`blankTitleReturns400NamingTheField`), so a failing test name already tells you what broke.

## Where it appears

| Path | Role |
| --- | --- |
| `*/src/test/java/...` in each module | provider, load generator and price-service tests |
| `PriceServiceBehaviorIT.java` | tests that start the whole service and a real provider |
| `pom.xml` | Surefire runs `*Test`; Failsafe runs `*IT` |

## How it works underneath

1. Maven's Surefire plugin starts a JVM and asks JUnit to find every class whose name matches `*Test`. Failsafe does the same for `*IT` later in the build.
2. For each test method (marked `@Test`) JUnit creates a **new instance** of the test class, runs `@BeforeEach` methods, runs the test, runs `@AfterEach` methods. So tests do not share fields.
3. A test passes if the method returns normally and fails if it throws. Assertion libraries (see `assertj.md`) throw when a check fails.
4. `@ParameterizedTest` runs the same method several times with different inputs.
5. Extensions add behavior. Spring's test support is a JUnit extension that builds an application context for the class.

## Walkthrough of this repository's use

```java
class InMemoryQuoteCacheTest {

    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private InMemoryQuoteCache cache(int maxEntries) {
        return new InMemoryQuoteCache(
                clock, new CacheProperties(Duration.ofSeconds(60), maxEntries), meters);
    }
```

A package-private class with fields and helper methods; each `@Test` below gets a fresh instance, so each starts with a new clock and a new meter registry.

```java
@Test
void aQuoteIsServedUntilSixtySecondsAfterItWasWritten() {
    InMemoryQuoteCache cache = cache(10);
    cache.put("sku-1", quote("42.00"));

    clock.advance(Duration.ofSeconds(60));
    assertThat(cache.get("sku-1")).isPresent();

    clock.advance(Duration.ofSeconds(1));
    assertThat(cache.get("sku-1")).isEmpty();
}
```

The test name is a sentence. Inside: put a quote in, move the fake clock, assert it is still there at 60 seconds and gone at 61.

```java
@BeforeAll
static void startProvider() throws IOException {
    provider = new FakeProvider(0);
    provider.start();
}
```

`@BeforeAll` on a **static** method runs once before all tests of the class: it starts the fake provider once. `@AfterAll` stops it. `@BeforeEach` resets the provider's mode before every test.

## Try it yourself

1. `./mvnw test` runs the `*Test` classes and prints one line per class.
2. `./mvnw test -Dtest=InMemoryQuoteCacheTest -pl price-service` runs one class.
3. `./mvnw test -Dtest='InMemoryQuoteCacheTest#readingDoesNotExtendTheLifeOfAQuote -pl price-service'` runs one method.
4. Open `target/surefire-reports/*.txt` for the results in text.
5. Change an expected value in a copy of a test and watch the failure message.

## Mistakes people make

- Tests that depend on the order of other tests or share state: JUnit may run them in any order.
- Names such as `test1`: when it fails you learn nothing.
- Asserting nothing, or asserting the same thing the code under test computed.
- Sleeping to wait for something: use a loop with a deadline or a fake clock.

## Check yourself

1. What makes a test fail? *An exception, usually from a failed assertion.*
2. Does a field set in one test survive to the next? *No, each test gets a new instance.*
3. What does `@ParameterizedTest` do? *Runs the method once per input.*
4. Which plugin runs `*IT` classes? *Failsafe.*
5. Why are test names sentences? *A failure explains itself.*

## Further reading

- JUnit User Guide: https://docs.junit.org/current/user-guide/
- Spring Boot 4.1.2, "Testing": https://docs.spring.io/spring-boot/4.1.2/reference/testing/index.html
