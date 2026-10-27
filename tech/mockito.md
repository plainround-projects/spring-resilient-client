# Mockito

## What it is

A **mock** is a fake object that records how it was called and returns answers you tell it to. Mockito is the library that creates mocks. You write `when(repository.findById("x")).thenReturn(...)` to script an answer and `verify(repository).save(book)` to check a call happened.

## Why this repository uses it

A unit test of one class should not need a database, a network or the rest of the application. Because each class receives its collaborators through its constructor, a test can pass mocks. `PriceServiceTest` checks the fallback logic with a mocked provider source and cache.

## Where it appears

| Path | Role |
| --- | --- |
| `application/PriceServiceTest.java` | mocks of `QuoteSource` and `QuoteCache` |
| `interfaces/web/PriceControllerTest.java` | `@MockitoBean` for the services and the tracer |

## How it works underneath

1. `mock(Type.class)` makes, at run time, a subclass or proxy of the type whose every method does nothing and returns a default (`null`, 0, an empty collection).
2. `when(call).thenReturn(value)` records what the next identical call should answer. `thenThrow(...)` makes it throw.
3. `verify(mock).method(args)` fails the test if that exact call did not happen. `inOrder(...)` also checks the order.
4. In a Spring test, `@MockitoBean` puts a mock into the application context **in place of** the real bean of that type.
5. Mocking classes (not just interfaces) needs a Java agent; the build passes `-XX:+EnableDynamicAgentLoading` so newer JDKs stay quiet.

## Walkthrough of this repository's use

```java
void whenTheProviderFailsTheLastGoodQuoteIsServedStaleWithItsAge() {
    when(source.fetch("sku-1")).thenThrow(new ProviderUnavailable("503"));
    when(cache.get("sku-1")).thenReturn(Optional.of(GOOD));

    PriceResult result = service.price("sku-1");

    assertThat(result.stale()).isTrue();
    assertThat(result.ageSeconds()).isEqualTo(37);
}
```

`when(source.fetch("sku-1")).thenThrow(new ProviderUnavailable("503"))` scripts the failure; `when(cache.get("sku-1")).thenReturn(Optional.of(GOOD))` scripts the remembered quote. The test then proves the use case returns a stale result with the right age.

```java
@MockitoBean private PriceService prices;
@MockitoBean private CatalogService catalog;
@MockitoBean private Tracer tracer;
```

The web-layer test replaces the services with mocks. The tracer is also a mock because the trace-id filter, which the web slice loads, needs one.

## Try it yourself

1. `./mvnw test -Dtest=PriceServiceTest -pl price-service`.
2. In a copy of a test, delete a `when(...)` line: the mock returns `null` or empty and the test fails in an informative way.
3. Change a `verify` to expect a different argument and read the "Wanted but not invoked" message.

## Mistakes people make

- Mocking everything, including simple value objects: use the real record.
- Verifying private details instead of behavior.
- Forgetting that an unscripted mock returns `null` / 0 / empty.
- Mocking the class under test.

## Check yourself

1. What does an unscripted mock method return? *A default: `null`, 0, empty.*
2. How do you check call order? *`inOrder(...)`.*
3. What does `@MockitoBean` do? *Replaces a bean in the Spring test context with a mock.*
4. When should you use the real object? *For simple values and when the real one is cheap and deterministic.*
5. Why can a lambda stand in for `IdGenerator`? *It has one method.*

## Further reading

- Mockito documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html
- Spring Framework 7.0.10, bean overrides (`@MockitoBean`): https://docs.spring.io/spring-framework/reference/7.0.10/testing/annotations/integration-spring/annotation-mockitobean.html
