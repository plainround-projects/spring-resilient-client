# Bean Validation (Jakarta Validation)

## What it is

Bean Validation is a standard way to state rules about data with annotations: `@NotBlank` (text must have a visible character), `@Min(1)` (a number must be at least 1), `@Size(max = 200)`, `@Pattern(regexp = "...")`. A **validator** reads the annotations and reports every rule that is broken. The standard is called Jakarta Validation; the library that implements it here is Hibernate Validator (unrelated to the database Hibernate despite the name).

## Why this repository uses it

The lesson asks for "validation at the boundary": a request with a blank title must be rejected with a 400 that names the field, before any business code runs.

## Where it appears

| Path | Rules |
| --- | --- |
| `interfaces/web/PriceController.java` | `@Pattern` on the SKU path variable |
| `infrastructure/ProviderProperties.java`, `CacheProperties.java` | rules on settings: a base URL, positive timeouts, a bounded cache size |

## How it works underneath

1. A constraint annotation is only a label. Something has to run the validator. Spring MVC does it in two places: when a parameter is marked `@Valid` (the body is validated after it is read), and when a controller method parameter carries a constraint annotation directly (Spring validates the method call).
2. The validator inspects the object, collects **violations** (the field path, the message and the rule) and does not stop at the first.
3. If there are violations, Spring throws an exception (`MethodArgumentNotValidException` for a body, `HandlerMethodValidationException` for method parameters) before the controller method runs. The advice class turns it into a 400 answer.
4. The same validator can check a settings record at startup (`@Validated` on a `@ConfigurationProperties` class).

## Walkthrough of this repository's use

```java
public PriceResponse price(
        @PathVariable
                @Pattern(
                        regexp = "[A-Za-z0-9._-]{1,64}",
                        message =
                                "sku must be 1 to 64 letters, digits, dots, dashes or"
                                        + " underscores")
                String sku) {
    return PriceResponse.from(service.price(sku));
}
```

`@Pattern(regexp = "[A-Za-z0-9._-]{1,64}")` on the path variable: only letters, digits, dot, dash and underscore, 1 to 64 characters. A SKU like `bad sku!` never reaches the use case.

```java
@Validated
@ConfigurationProperties("price.provider")
public record ProviderProperties(
        @NotBlank String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout) {

    /**
     * Checks that both timeouts are positive.
     *
     * @return true when neither timeout is zero or negative
     */
    @AssertTrue(message = "connect-timeout and read-timeout must be positive")
    public boolean isTimeoutsPositive() {
        return connectTimeout.isPositive() && readTimeout.isPositive();
    }
}
```

Validation also guards the settings. `@NotBlank` on the base URL and an `@AssertTrue` method that insists both timeouts are positive mean the application cannot start with a call that waits forever.

**Every production class that carries Bean Validation annotations or `@Valid`:**

| File | What it is |
| --- | --- |
| `price-service/src/main/java/dev/plainround/price/infrastructure/CacheProperties.java` | How long a good quote may be served stale, and how many are remembered. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderProperties.java` | Where the provider is and how long a call may take. |
| `price-service/src/main/java/dev/plainround/price/interfaces/web/PriceController.java` | HTTP face of pricing. |

## Try it yourself

1. `curl -si 'localhost:18104/price/bad%20sku!'` gives a 400 whose `errors` names `sku`.
2. `./mvnw test -pl price-service -Dtest=ProviderPropertiesTest` shows startup failing for a zero timeout.

## Mistakes people make

- Putting the annotations on the domain object instead of a request type: it couples what the API accepts to what it stores.
- Forgetting `@Valid` on the parameter, or on the element type of a list.
- Using `@NotNull` for text: an empty string passes. Use `@NotBlank`.
- Trusting the web rules alone: the domain records re-check their own rules, so a bug in the web layer cannot create a broken object.

## Check yourself

1. Which annotation rejects `"   "`? *`@NotBlank`.*
2. What does `@Valid` on a parameter do? *Makes Spring run the validator on it.*
3. How is a violation in the 7th basket line reported? *With a path such as `lines[6].quantity`.*
4. Why is the request a different type from the domain object? *A client must not be able to set fields like `id`.*
5. What does `@Validated` on a settings record do? *Validates it when it is built at startup.*

## Further reading

- Jakarta Validation 3.1 specification: https://jakarta.ee/specifications/bean-validation/3.1/
- Spring Framework 7.0.10, "Validation": https://docs.spring.io/spring-framework/reference/7.0.10/core/validation/beanvalidation.html
- Hibernate Validator constraints: https://docs.jboss.org/hibernate/validator/9.0/reference/en-US/html_single/
