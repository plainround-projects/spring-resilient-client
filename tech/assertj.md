# AssertJ

## What it is

AssertJ is a library of assertions: statements that check a result and throw a clear error when the result is wrong. You write `assertThat(actual)` and then chain what you expect: `.isEqualTo(5)`, `.contains("a")`, `.hasSize(3)`. The failure message shows both values.

## Why this repository uses it

Tests should read like sentences, and a failure should say what was expected and what happened. AssertJ also checks exceptions (`assertThatThrownBy`) without try/catch.

## Where it appears

Every test class imports `org.assertj.core.api.Assertions.assertThat`. It comes with `spring-boot-starter-test` (and as a direct test dependency of the plain-Java modules).

## How it works underneath

1. `assertThat(x)` returns an object specialized for the type of `x` (a string assertion, a list assertion, a number assertion). That is why IDEs can suggest the right checks.
2. Each method such as `isEqualTo` compares and, if the check fails, throws `AssertionError` with a built message.
3. JUnit sees the thrown error and marks the test failed (see `junit-5.md`).
4. `as("text")` or a lambda `as(() -> body)` adds a description shown on failure.

## Walkthrough of this repository's use

```java
void doesNotRetryOnATimeoutAndAnswers504InAboutTheReadTimeout() {
    provider.mode(ProviderMode.HANG);
    long started = System.nanoTime();

    ResponseEntity<String> response = get("/price/sku-hang");
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(response.getStatusCode().value()).isEqualTo(504);
    assertThat(response.getHeaders().getContentType().toString())
            .startsWith("application/problem+json");
    assertThat(elapsed)
            .as("read timeout 500 ms, not the provider's 10 s")
            .isBetween(Duration.ofMillis(400), Duration.ofSeconds(4));
    assertThat(provider.callsReceived()).as("a timeout is not retried").isEqualTo(1);
}
```

`assertThat(elapsed).as("read timeout 500 ms, not the provider's 10 s").isBetween(Duration.ofMillis(400), Duration.ofSeconds(4))` checks that the call took about the timeout. `.as(...)` is the message shown if it fails. `assertThat(provider.callsReceived()).as("a timeout is not retried").isEqualTo(1)` reads the **provider's own** counter, an independent witness.

**Every test class that uses AssertJ (all of them), with its tests:**

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

1. Open a test and change an expected number, then run `./mvnw test -Dtest=<class>` and read the message: `expected: 6 but was: 5`.
2. Try `assertThat(List.of(1, 2)).containsExactly(2, 1)` in `jshell` with the AssertJ jar on the classpath, or in a scratch test, to see the order-sensitive message.
3. Search the tests for `.as(` to see failure descriptions.

## Mistakes people make

- `assertThat(a == b).isTrue()`: the failure says only "expected true". Use `assertThat(a).isEqualTo(b)`.
- Forgetting the last call: `assertThat(x)` alone checks nothing.
- Comparing `BigDecimal` with `isEqualTo`: `1.0` and `1.00` differ. This repository uses `isEqualByComparingTo` for money.

## Check yourself

1. What does `assertThat(x).isEqualTo(y)` do on failure? *Throws `AssertionError` showing both values.*
2. How do you expect an exception? *`assertThatThrownBy(() -> code).isInstanceOf(...)`.*
3. What does `extracting` do? *Maps each element of a collection to a field.*
4. What is `.as(...)` for? *A description shown when the assertion fails.*
5. Why `isEqualByComparingTo` for `BigDecimal`? *It ignores trailing zeros.*

## Further reading

- AssertJ Core documentation (version managed by Spring Boot 4.1.2, 3.27): https://assertj.github.io/doc/
