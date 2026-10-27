# Spring Web MVC (controllers, filters, error advice)

## What it is

Spring Web MVC is the part of Spring that handles HTTP. A **controller** is a class whose methods answer requests. You mark a method with the URL and the HTTP method it answers (`@GetMapping("/books")`), and Spring calls it, reads the request into the method's parameters, and writes the return value as the response body (as JSON here).

## Why this repository uses it

The lesson is about walking from an HTTP request to a method and back: how input is checked, how an exception becomes an error answer, how a test exercises just this layer. In this project the controller is thin; the interesting work is the provider call behind it.

## Where it appears

| Path | Role |
| --- | --- |
| `interfaces/web/PriceController.java` | `GET /price/{sku}` |
| `interfaces/web/CatalogController.java` | `GET /skus` and `GET /skus/{sku}`, which never call the provider |
| `interfaces/web/ProviderExceptionHandler.java` | 504 for a timeout, 503 with `Retry-After` for other provider failures |
| `interfaces/web/TraceIdFilter.java` | puts the trace id in a response header |

## How it works underneath

1. The embedded Tomcat accepts a TCP connection and parses the HTTP text into a request object.
2. The request passes through the **filter chain**: classes that can look at or change every request and response before and after the rest (here: assign an id, add a trace id).
3. A single servlet called the **DispatcherServlet** picks the controller method whose mapping matches the URL and method.
4. **Argument resolvers** fill the method's parameters: `@PathVariable` from the URL, `@RequestParam` from the query string, `@RequestBody` from the JSON body (parsed by Jackson), and `@Valid` triggers validation.
5. The method runs. Its return value is turned into JSON and written with the status given by `@ResponseStatus` (200 by default).
6. If anything throws, the **exception resolvers** look for a matching `@ExceptionHandler` method in a class marked `@RestControllerAdvice` (one class that serves all controllers) and use its result as the response.

## Walkthrough of this repository's use

```java
@GetMapping("/price/{sku}")
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

`@GetMapping("/price/{sku}")` answers `GET /price/sku-1`. `@PathVariable @Pattern(...) String sku` reads the path segment and checks it against a regular expression (letters, digits, dots, dashes, underscores, 1 to 64 characters). The method only converts the use case's `PriceResult` to the response record.

The error advice:

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

`NoPriceAvailable` means the provider failed and nothing recent is remembered. The handler returns a `ResponseEntity<ProblemDetail>` so it can set a status and a header: **504** when the cause was a timeout, **503 with `Retry-After: 1`** for every other cause. The two mean different things to a caller: "the provider took too long" and "the provider cannot serve right now".

The filter:

```java
protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
    Span span = tracer.currentSpan();
    if (span != null) {
        response.setHeader(HEADER, span.context().traceId());
    }
    chain.doFilter(request, response);
}
```

It asks the tracer for the current span and, if there is one, copies its trace id into the `X-Trace-Id` response header. It is ordered to run just inside Spring's observation filter, where the span already exists.

## Try it yourself

1. After `make up`: `curl -s localhost:18104/price/sku-1` prints the price JSON.
2. `curl -si localhost:18104/price/sku-1` shows the `X-Trace-Id` header.
3. `curl -s localhost:18104/skus/sku-1` shows the catalog entry, which never calls the provider.

4. `./mvnw test -Dtest=PriceControllerTest` runs the web-layer tests without starting the whole application.

## Mistakes people make

- Putting business rules in the controller: it becomes impossible to test without HTTP.
- Returning a database entity from a controller: lazy data may blow up while the JSON is written.
- Forgetting `@Valid` on the body parameter: validation silently does not run.
- Using `int` for an optional query parameter: "absent" becomes 0.
- Two advice classes whose handlers both match: the one with the higher precedence wins, and the other is silently ignored. This repository has one advice.

## Check yourself

1. What does the DispatcherServlet do? *Picks the controller method for a request.*
2. What does `@RestController` add to `@Controller`? *`@ResponseBody`: return values become the body.*
3. Where does an exception from a controller go? *To a matching `@ExceptionHandler` in a `@RestControllerAdvice`.*
4. Why does the filter remove the request id in `finally`? *Threads are reused; the id must not leak into the next request.*
5. What does `@PathVariable` read? *A value from the URL path.*

## Further reading

- Spring Framework 7.0.10, "Spring Web MVC": https://docs.spring.io/spring-framework/reference/7.0.10/web/webmvc.html
- Exception handling: https://docs.spring.io/spring-framework/reference/7.0.10/web/webmvc/mvc-controller/ann-exceptionhandler.html
