# Problem Details (RFC 9457, application/problem+json)

## What it is

When an HTTP request fails, the server should say why in a shape that programs can read. **Problem Details** is a standard (RFC 9457) for that shape: a JSON object with a few agreed fields, sent with the content type `application/problem+json`. The fields: `type` (a URI that names the kind of problem), `title` (a short human summary), `status` (the HTTP status code), `detail` (an explanation of this occurrence) and `instance` (the URL that failed). A service may add its own fields; this repository adds `errors` for validation.

## Why this repository uses it

The lesson asks for "one error shape" for the project's errors and the framework's own: an unknown id, a malformed JSON body, a failed validation. A client that parses errors then needs one parser. Spring has the type `ProblemDetail` for it.

## Where it appears

| Path | What it does |
| --- | --- |
| `interfaces/web/ProviderExceptionHandler.java` | builds the 504 and 503 problems, with a `Retry-After` header on 503 |
| `application.properties` | `spring.mvc.problemdetails.enabled=true`, the lesson's switch for the framework's own errors |

## How it works underneath

1. A controller or the framework throws an exception.
2. An `@ExceptionHandler` method in the advice class returns a `ProblemDetail` object (or a `ResponseEntity` that holds one).
3. Spring converts it to JSON with the content type `application/problem+json`. Fields you add with `setProperty(name, value)` are written at the top level of the JSON.
4. The `instance` field is filled by Spring from the request URL when you leave it empty.
5. For the framework's own exceptions (a wrong HTTP method, a body that is not JSON) the base class `ResponseEntityExceptionHandler` already knows how to build a `ProblemDetail`. The advice here extends that base class so those errors share the shape.

## Walkthrough of this repository's use

```java
@ExceptionHandler(NoPriceAvailable.class)
public ResponseEntity<ProblemDetail> handleNoPrice(NoPriceAvailable exception) {
    if (exception.failure() instanceof ProviderTimeout) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(
                        problem(
                                HttpStatus.GATEWAY_TIMEOUT,
                                "provider-timeout",
                                "Provider timed out",
                                exception.getMessage()));
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(
                    problem(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "provider-unavailable",
                            "Provider unavailable",
                            exception.getMessage()));
}
```

The handler returns `ResponseEntity<ProblemDetail>` because it also sets the status line and a header. 504 means the provider timed out; 503 means it could not serve (with `Retry-After: 1`, "try again in a second"). A real 503 body:

```json
{"detail":"no price available for sku-never-priced","status":503,"title":"Provider unavailable","type":"urn:plainround:problem:provider-unavailable"}
```

## Try it yourself

1. With the provider down (`curl -X PUT 'localhost:18103/admin/mode?mode=fail503'`): `curl -si localhost:18104/price/sku-never-priced` shows 503, `Retry-After: 1` and the problem body.
2. With `mode=hang` the same call returns 504 after about two seconds.

3. `./mvnw test -Dtest=PriceControllerTest` asserts the shapes.

## Mistakes people make

- Returning a plain string or a custom error object for each error: clients need a parser per case.
- Leaking the exception message of an unexpected error (a SQL string, a host name): the last-resort handler here answers a generic "The request failed." and logs the details instead.
- Using `detail` as a stable code: it is for people. Use `type` for programs.
- Forgetting that `status` in the body should match the HTTP status line.

## Check yourself

1. Which content type marks a problem document? *`application/problem+json`.*
2. Which field names the kind of problem? *`type`.*
3. How do you add a custom field in Spring? *`problem.setProperty(name, value)`.*
4. Why a generic 500 body? *Not to leak internals.*
5. What does the base class `ResponseEntityExceptionHandler` give you? *Problem documents for the framework's own exceptions.*

## Further reading

- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457.html
- Spring Framework 7.0.10, "Error Responses": https://docs.spring.io/spring-framework/reference/7.0.10/web/webmvc/mvc-ann-rest-exceptions.html
