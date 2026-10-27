# JSON and Jackson

## What it is

JSON is a text format for data: objects `{"name": "Dune"}`, lists `[1, 2]`, strings in double quotes, numbers, `true`, `false`, `null`. Jackson is the Java library that converts Java objects to JSON text (**serialization**) and JSON text to Java objects (**deserialization**). Spring Boot 4.1.2 ships Jackson 3, whose packages start with `tools.jackson` (older versions used `com.fasterxml.jackson`).

## Why this repository uses it

Every endpoint receives and returns JSON. Records are used for every request and answer, because Jackson can read and write them directly.

## Where it appears

| Path | Role |
| --- | --- |
| `interfaces/web/PriceResponse.java` | the JSON the price API returns |
| `infrastructure/ProviderCall.java` (record `QuoteBody`) | the JSON the provider returns, read into a record |
| `provider/.../FakeProvider.java` | builds the provider's JSON by hand, as plain strings |
| tests | read answers with the JsonPath library (`$.errors[0].field`) |

## How it works underneath

A **JSON object** is a set of named values in curly braces, `{"title": "Dune", "pages": 412}`; a **JSON array** is an ordered list in square brackets, `[1, 2, 3]`. Values can themselves be objects or arrays, so JSON nests. Strings use double quotes. There is no date type: dates travel as text.

1. Spring MVC has **message converters**. For `application/json` the converter is backed by Jackson.
2. On a request, the converter reads the body bytes and calls Jackson to build the record named by the `@RequestBody` parameter. Unknown fields are ignored by Spring Boot's setup; a missing field becomes `null` (or `0` for `int`).
3. On the answer, Jackson walks the record's components and writes `{"name": value, ...}` using the component names.
4. If the body is not valid JSON, Jackson throws, and Spring turns it into a 400 (see `problem-details.md`).
5. `BigDecimal` is written as a JSON number with its digits kept, so `42.00` stays `42.00`.

## Walkthrough of this repository's use

```java
    public record QuoteBody(BigDecimal price) {}
}
```

The provider answers `{"price":42.00}`. `restClient.get().uri("/quotes/{sku}", sku).retrieve().body(QuoteBody.class)` reads that into a `QuoteBody`. If the provider sends `{}`, `price` is `null` and the code treats the answer as unusable.

```java
public record PriceResponse(BigDecimal price, boolean stale, long ageSeconds) {

    /**
     * Converts a domain result.
     *
     * @param result the result
     * @return its API shape
     */
    public static PriceResponse from(PriceResult result) {
        return new PriceResponse(result.price(), result.stale(), result.ageSeconds());
    }
}
```

The price API's JSON is `{"price":42.00,"stale":false,"ageSeconds":0}`; `stale` and `ageSeconds` come from the domain's `PriceResult`.

The fake provider writes its JSON by hand (`"{\"price\":" + ... + "}"`) because it is deliberately a plain Java program without Jackson.

**Every record that is a JSON shape (a request or an answer):**

| File | What it is |
| --- | --- |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderCall.java` | One protected attempt to ask the provider for a price. |
| `price-service/src/main/java/dev/plainround/price/interfaces/web/PriceResponse.java` | A price as the API shows it. |

## Try it yourself

1. `curl -s localhost:18104/price/sku-1 | jq .` pretty-prints the JSON (see `jq.md`).
2. Read the provider's own JSON, written by hand: `curl -s localhost:18103/quotes/sku-1` prints `{"price":...}`.
3. In a test, `JsonPath.read(body, "$.errors[0].field")` extracts one value; open a `*ScenarioIT` or `*Test` to see it used.

## Mistakes people make

- Forgetting the `Content-Type: application/json` header: Spring answers 415.
- Using `double` for money: use `BigDecimal` (this repository does for prices).
- Expecting a missing number to be an error: an `int` component becomes 0. The `@Min(1)` rule on `pages` or `quantity` catches that.
- Trailing commas or single quotes: they are not JSON.

## Check yourself

1. What are serialization and deserialization? *Object to JSON text, and JSON text to object.*
2. How do record components become JSON keys? *By their names.*
3. What happens to invalid JSON? *Jackson fails and Spring answers 400.*
4. Why use `BigDecimal` for prices? *It keeps exact decimals.*
5. What do the `tools.jackson` packages tell you? *The project uses Jackson 3.*

## Further reading

- JSON: https://www.json.org/
- Spring Boot 4.1.2, "JSON": https://docs.spring.io/spring-boot/4.1.2/reference/features/json.html
- Jackson project: https://github.com/FasterXML/jackson
