# Java 25

## What it is

Java is a programming language. You write source code in `.java` files. A tool called the **compiler** (`javac`) turns each file into **bytecode** (`.class` files). A program called the **JVM** (Java Virtual Machine, started by the `java` command) reads the bytecode and runs it. A **JDK** (Java Development Kit) is the compiler, the JVM and the standard library in one download. A **JRE** is the JVM and the standard library without the compiler.

## Why this repository uses it

The lesson is about Spring, and Spring is a Java framework. This repository is written for Java 25: the lesson names no version, so the repository uses the current long-term-support release.

## Where it appears

| Path | What it does with Java |
| --- | --- |
| `provider/src/main/java/` | the fake provider, plain Java with no framework |
| `loadgen/src/main/java/` | the load generator, plain Java |
| `price-service/src/main/java/dev/plainround/price/` | the Spring Boot service in four layers |
| `*/src/test/java/` | the tests of each module |

## How it works underneath

A few more words used below. A **thread** is one line of execution inside a running program; a program can run many at once, and a web server uses one per request. An **exception** is an object a method throws when something goes wrong; it travels up the chain of callers until some code catches it, and if none does the thread ends with an error. **`null`** is the value of a reference that points to nothing. A **generic type** such as `List<Book>` is a list that holds only books; the part in angle brackets is a type parameter. `static` means "belongs to the class, not to one object"; `final` on a field means "set once, never changed"; `private` means "visible only inside this class". `Optional<T>` is a box that holds a value or is empty, used instead of `null` for "maybe nothing".

1. `javac` reads `.java` files and writes `.class` files. In this repository Maven calls it for you.
2. A **package** is a folder of classes. The line `package dev.plainround.price.domain;` at the top of a file says which folder the class belongs to.
3. A **class** describes a kind of object: its data (fields) and its actions (methods). A **record** is a short form of a class that only holds data and cannot change after it is built. An **interface** lists methods without writing their code; other classes promise to provide them.
4. At run time the JVM loads classes when they are first needed, runs `public static void main(String[] args)` of the class you name, and frees memory that nothing points to any more (**garbage collection**).
5. An **annotation** such as `@Override` or `@Test` is a label written in front of a class or method. The compiler, a test runner or a framework reads the label and acts on it.
6. A **lambda** such as `line -> line.sku()` is a small function written inline. A **stream** is a pipeline that applies lambdas to a list: `list.stream().map(...).toList()`.

## Walkthrough of this repository's use

**Records.** A record declares its fields in the header and gets the constructor, the accessors and `equals`, `hashCode` and `toString` for free.

```java
public record PriceResult(BigDecimal price, boolean stale, long ageSeconds) {

    /**
     * Wraps a quote that has just been fetched.
     *
     * @param quote the fresh quote
     * @return a result that is not stale
     */
    public static PriceResult fresh(Quote quote) {
        return new PriceResult(quote.price(), false, 0);
    }

    /**
     * Wraps the last good quote, labeled stale with its age.
     *
     * @param quote the cached quote
     * @param now the current time
     * @return a result that is stale
     */
    public static PriceResult stale(Quote quote, Instant now) {
        return new PriceResult(
                quote.price(), true, Duration.between(quote.fetchedAt(), now).toSeconds());
    }
}
```
`PriceResult` holds three values and offers two named ways to build one, `fresh(quote)` and `stale(quote, now)`. The caller never has to remember which flag combination means what.

**Sealed classes and `switch` patterns.** A sealed class lists exactly which classes may extend it:

```java
public abstract sealed class ProviderFailure extends RuntimeException
        permits ProviderTimeout, ProviderUnavailable, ProviderBusy {
```

Because the compiler knows the three subtypes (`ProviderTimeout`, `ProviderUnavailable`, `ProviderBusy`), a `switch` over a `ProviderFailure` that names all three needs no `default`; if you add a fourth subtype, every such `switch` stops compiling until you handle it. `ProviderErrors.translate` uses the other new feature, **pattern matching**: `case ResourceAccessException ioProblem when isTimeout(ioProblem) ->` tests the type of the value, names it `ioProblem`, and adds a condition (`when`).

```java
return switch (exception) {
    case ProviderFailure failure -> failure;
    case InvocationRejectedException rejected -> new ProviderBusy(rejected);
    case ResourceAccessException ioProblem when isTimeout(ioProblem) ->
            new ProviderTimeout(ioProblem);
    case ResourceAccessException ioProblem ->
            new ProviderUnavailable("the provider could not be reached", ioProblem);
    case HttpServerErrorException serverError ->
            new ProviderUnavailable(
                    "the provider answered " + serverError.getStatusCode().value(),
                    serverError);
    default -> new ProviderUnavailable("unexpected answer from the provider", exception);
};
```

**Interfaces as ports.** `QuoteSource` and `QuoteCache` are interfaces in `domain`; `HttpQuoteSource` and `InMemoryQuoteCache` implement them. The use case depends on the interfaces only, so a test can hand it any fake.

**Lambdas and streams.** `InMemoryQuoteCache` and `PriceService` use `Optional`: `cache.get(sku).map(quote -> PriceResult.stale(quote, clock.instant())).orElseThrow(...)` reads "if there is a quote, turn it into a stale result; if not, throw".

**Checked and unchecked exceptions.** All the project's errors extend `RuntimeException` (unchecked): the compiler does not force every caller to catch them. Each has a name that says what went wrong, such as `ProviderTimeout`, and one place (the "advice" class) turns them into HTTP answers.

**Compiler settings.** The build runs `javac` with `-Xlint:all -Werror`: every warning becomes an error. That keeps unchecked casts and deprecated calls out of the code.

**Every Java file in this repository, and what it is.** The descriptions below are the first sentence of each class's own documentation comment (the `/** ... */` block above the class, called Javadoc).

| File | What it is |
| --- | --- |
| `loadgen/src/main/java/dev/plainround/loadgen/LoadMain.java` | Command line: `java -jar loadgen.jar --target http://host/price/{sku` --skus 10 --rps 100 --seconds 30 [--side http://host/skus/sku-1 --side-rps 10]}. |
| `loadgen/src/main/java/dev/plainround/loadgen/LoadRunner.java` | Sends requests at a fixed rate whether or not the earlier ones have come back (an open loop), so a slow target shows up as latency instead of quietly lowering the load. |
| `loadgen/src/main/java/dev/plainround/loadgen/Report.java` | The outcome of one load run against one target. |
| `price-service/src/main/java/dev/plainround/price/PriceServiceApplication.java` | Entry point of the price service. |
| `price-service/src/main/java/dev/plainround/price/application/CatalogService.java` | Reads the product catalog, which does not depend on the provider. |
| `price-service/src/main/java/dev/plainround/price/application/PriceService.java` | Prices a SKU: a fresh quote when the provider answers, the last good one, labeled stale, when it does not. |
| `price-service/src/main/java/dev/plainround/price/domain/Catalog.java` | Port: the products the shop sells. |
| `price-service/src/main/java/dev/plainround/price/domain/CatalogEntry.java` | A product the shop sells. |
| `price-service/src/main/java/dev/plainround/price/domain/NoPriceAvailable.java` | The provider failed and there is no recent quote to fall back on. |
| `price-service/src/main/java/dev/plainround/price/domain/PriceResult.java` | What the price API answers: the price, whether it is the last one we knew rather than a fresh one, and its age. |
| `price-service/src/main/java/dev/plainround/price/domain/ProviderBusy.java` | Too many calls to the provider are already in flight; this one was refused at once instead of queueing. |
| `price-service/src/main/java/dev/plainround/price/domain/ProviderFailure.java` | The provider could not give a price right now. |
| `price-service/src/main/java/dev/plainround/price/domain/ProviderTimeout.java` | The provider did not answer within the read or connect timeout. |
| `price-service/src/main/java/dev/plainround/price/domain/ProviderUnavailable.java` | The provider said it cannot serve (503), could not be reached, or sent nothing usable. |
| `price-service/src/main/java/dev/plainround/price/domain/Quote.java` | A price the provider gave us at a moment in time. |
| `price-service/src/main/java/dev/plainround/price/domain/QuoteCache.java` | Port: the last good quote per SKU, remembered so it can be served, labeled stale, while the provider is down. |
| `price-service/src/main/java/dev/plainround/price/domain/QuoteSource.java` | Port: where fresh quotes come from. |
| `price-service/src/main/java/dev/plainround/price/domain/SkuNotFound.java` | The catalog has no product with the requested code. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/CacheProperties.java` | How long a good quote may be served stale, and how many are remembered. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ClockConfig.java` | The one place the real clock is created; tests replace this bean. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/HttpQuoteSource.java` | Adapter: the quote port over the protected provider call. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/InMemoryCatalog.java` | Ten fixed products, `sku-0` to `sku-9`: the catalog the shop's other endpoints serve. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/InMemoryQuoteCache.java` | Remembers the last good quote per SKU in memory. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/NoQuoteCache.java` | Remembers nothing: the "before" run of the benchmark (`price.protection=none`) has no cache. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderCall.java` | One protected attempt to ask the provider for a price. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderClientConfig.java` | Builds the RestClient for the provider, with a connect timeout and a read timeout on every call. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderErrors.java` | Turns what the HTTP client and the resilience proxies throw into the domain's three named provider failures. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderProperties.java` | Where the provider is and how long a call may take. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ResilienceConfig.java` | Switches on Spring Framework's own resilience annotations (`@Retryable`, `@ConcurrencyLimit`), the package `org.springframework.resilience.annotation`, not the older separate Spring Retry library. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/UnprotectedQuoteSource.java` | The "before" of the lesson's before-and-after table, selected with `price.protection=none`: a plain call with no timeout, no retry, no concurrency limit and no cache. |
| `price-service/src/main/java/dev/plainround/price/interfaces/web/CatalogController.java` | HTTP face of the catalog: an endpoint that never calls the provider, used to show the provider's sickness does not spread. |
| `price-service/src/main/java/dev/plainround/price/interfaces/web/PriceController.java` | HTTP face of pricing. |
| `price-service/src/main/java/dev/plainround/price/interfaces/web/PriceResponse.java` | A price as the API shows it. |
| `price-service/src/main/java/dev/plainround/price/interfaces/web/ProviderExceptionHandler.java` | The one place errors become `application/problem+json`. |
| `price-service/src/main/java/dev/plainround/price/interfaces/web/TraceIdFilter.java` | Puts the id of the request's trace in the `X-Trace-Id` response header, so a caller (or the README's trace capture) can find exactly this request in the trace backend. |
| `provider/src/main/java/dev/plainround/provider/FakeProvider.java` | A quote provider you control. |
| `provider/src/main/java/dev/plainround/provider/JsonLog.java` | One JSON object per line on standard output: level, message and time, nothing else. |
| `provider/src/main/java/dev/plainround/provider/ProviderMain.java` | Starts the fake provider on `PORT` (default 8081) and stops it when the process is told to. |
| `provider/src/main/java/dev/plainround/provider/ProviderMode.java` | How the fake provider answers a quote request. |

**Every test class, with the tests inside it** (a test method's name is a sentence about behavior; see `junit.md`):

- `loadgen/src/test/java/dev/plainround/loadgen/LoadgenTest.java`:
  - `percentilesUseTheNearestRank`
  - `anEmptyRunReportsZeros`
  - `theJsonLineCarriesEveryNumber`
  - `optionsParseInPairs`
  - `theRunnerHoldsTheRateAndCountsErrorsAndStaleAnswers`
  - `mainRunsThePriceTargetAndTheSideTargetAndPrintsOneJsonLineEach`
  - `anUnreachableTargetCountsAsErrors`
  - `aMissingTargetIsRejected`
- `price-service/src/test/java/dev/plainround/price/ApplicationConfigurationTest.java`:
  - `everyProviderCallHasBothTimeoutsAndTheRetryRulesFromTheLesson`
  - `virtualThreadsJsonLogsGracefulShutdownAndFullTracingAreOn`
- `price-service/src/test/java/dev/plainround/price/ArchitectureTest.java`:
  - `layersOnlyDependInward`
  - `domainDependsOnNothingButTheJdk`
  - `applicationKnowsOnlyTheDomainTheJdkAndLogging`
  - `noFieldInjection`
  - `controllersLiveInTheInterfacesLayer`
  - `noPackageCycles`
  - `noSelfInvocationOfProxiedMethods`
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
- `price-service/src/test/java/dev/plainround/price/application/PriceServiceTest.java`:
  - `aFreshQuoteIsReturnedAndRemembered`
  - `whenTheProviderFailsTheLastGoodQuoteIsServedStaleWithItsAge`
  - `aSkuNeverPricedIsNoPriceAvailableAndKeepsTheReason`
  - `theCatalogServiceListsAndNamesAMissingSku`
- `price-service/src/test/java/dev/plainround/price/domain/DomainTest.java`:
  - `aFreshResultIsNotStaleAndHasNoAge`
  - `aStaleResultCarriesTheAgeOfTheQuote`
  - `aQuoteWithoutAPositivePriceIsAProviderFailure`
  - `theNamedFailuresSayWhy`
- `price-service/src/test/java/dev/plainround/price/infrastructure/InMemoryQuoteCacheTest.java`:
  - `aQuoteIsServedUntilSixtySecondsAfterItWasWritten`
  - `readingDoesNotExtendTheLifeOfAQuote`
  - `aNewQuoteReplacesTheOldOneAndRestartsTheClock`
  - `theOldestEntryIsDroppedWhenTheBoundIsReached`
  - `hitsAndMissesAreCounted`
  - `theUnprotectedVariantsRememberNothingAndTheCatalogHasTenProducts`
- `price-service/src/test/java/dev/plainround/price/infrastructure/ProviderErrorsTest.java`:
  - `aTimeoutInsideAnIoErrorIsAProviderTimeout`
  - `aRefusedConnectionIsUnavailableNotATimeout`
  - `aRejectionByTheConcurrencyLimitIsBusy`
  - `aServerErrorAndAnythingElseAreUnavailable`
  - `aDomainFailurePassesThroughUnchanged`
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
- `price-service/src/test/java/dev/plainround/price/support/MutableClock.java`: a helper used by the tests
- `provider/src/test/java/dev/plainround/provider/FakeProviderTest.java`:
  - `normalModeAnswers200WithADeterministicPriceAndCountsTheCall`
  - `fail503ModeAnswers503AtOnce`
  - `failNextAnswers503ForExactlyThatManyCallsThenReturnsToTheMode`
  - `slowModeAnswersAfterTheConfiguredDelay`
  - `hangModeIsSilentLongerThanAClientWillWaitButStillCountsTheCall`
  - `theAdminEndpointSwitchesTheModeAtRunTime`
  - `anUnknownModeIs400AndAGetOnTheAdminEndpointIs405`
  - `maxInFlightRecordsTheMostCallsServedAtOnce`
  - `resetZeroesTheCountersAndMetricsExposeThem`
  - `modeNamesParseIgnoringCaseAndRejectNonsense`
  - `theMainEntryPointStartsAProviderThatAnswersHealth`
  - `hangLastsAsLongAsConfiguredAndThenAnswersEmptyJson`
  - `jsonLogEscapesQuotesAndNewlines`

## Try it yourself

1. `java -version` prints the JDK you have. This repository needs 25 or newer.
2. `./mvnw -q compile` compiles the code. Afterwards `ls target/classes` shows the `.class` files in the same folders as the packages.
3. `javap -cp target/classes dev.plainround.price.domain.PriceResult` (from `price-service/`) prints the methods the compiler made for the record: the accessors, `equals`, `hashCode`, `toString`.
4. `jshell` starts an interactive prompt; type `record Point(int x, int y) {}` then `new Point(1,2)` to see a record's `toString`.

## Mistakes people make

- Changing a record's data: it cannot be done. Build a new record instead, as `markedRead()` does.
- Using `==` on strings: it compares identity, not content. Use `.equals`.
- Catching `Exception` everywhere: it hides bugs. This repository catches one named type where it can act on it, and the advice class handles the rest in one place.
- Building with an older JDK than the code needs: `./mvnw` says "release version 25 not supported". Install a newer JDK.
- Long methods. The house style here is short methods with names that say what they do.

## Check yourself

1. What is the difference between the JDK and the JRE? *The JDK also has the compiler.*
2. What does a record's compact constructor do? *Runs before the fields are set; used to check values.*
3. Why does `markedRead()` return a new `Book`? *Records cannot change after they are built.*
4. What does `-Werror` do to a compiler warning? *Turns it into an error that fails the build.*
5. What is an interface for, in this repository? *It names what a layer needs (a "port"), so another class can provide it.*

## Further reading

- The Java Language Specification and the `java.base` API for Java 25: https://docs.oracle.com/en/java/javase/25/
- Records: https://docs.oracle.com/en/java/javase/25/language/records.html
- Sealed classes and pattern matching for `switch`: https://docs.oracle.com/en/java/javase/25/language/sealed-classes-and-interfaces.html
