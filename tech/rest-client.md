# RestClient (calling another service over HTTP)

## What it is

`RestClient` is Spring's synchronous HTTP client: you build a request (`get()`, `uri(...)`), send it (`retrieve()`) and read the answer into an object (`body(Type.class)`). "Synchronous" means the calling thread waits for the answer. An HTTP call can fail in three ways: it cannot connect, it connects but the answer does not come in time (a **read timeout**), or the server answers with an error status (for example 503).

## Why this repository uses it

The lesson is about calling a sick dependency safely. The first rule is that every call has a timeout, so a slow provider can only cost a fixed amount of time. `RestClient` sits on top of a request factory that carries the timeouts.

## Where it appears

| Path | Role |
| --- | --- |
| `infrastructure/ProviderClientConfig.java` | builds the client with the timeouts |
| `infrastructure/ProviderCall.java` | uses it to call `GET /quotes/{sku}` |
| `infrastructure/ProviderErrors.java` | names what the client throws |
| `infrastructure/ProviderProperties.java` | the base URL and the two timeouts, validated |
| `PriceServiceBehaviorIT.java` | tests every failure mode against a real provider |

## How it works underneath

1. The client builds a request and hands it to a **request factory**, which does the real network work. Here that is `JdkClientHttpRequestFactory`, which wraps the JDK's own `java.net.http.HttpClient`.
2. The **connect timeout** is set on the JDK `HttpClient` (how long opening the TCP connection may take). The **read timeout** is set on the factory (how long to wait for the answer once connected).
3. When a timeout fires, the JDK throws `HttpTimeoutException`. Spring wraps an I/O failure in `ResourceAccessException`. So the code finds the timeout by looking through the exception's causes.
4. When the server answers 5xx, `retrieve()` throws `HttpServerErrorException`; a 503 is the subclass `HttpServerErrorException.ServiceUnavailable`. 4xx throws `HttpClientErrorException`.
5. Spring Boot's `RestClient.Builder` is already connected to the metrics and tracing system, so every call is timed and appears as a span in a trace.

## Walkthrough of this repository's use

```java
public RestClient providerRestClient(
        RestClient.Builder builder, ProviderProperties properties) {
    HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.readTimeout());
    return builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
}
```

- It starts from Spring Boot's builder (`RestClient.Builder builder`), so metrics and tracing are included.
- `HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build()` makes the JDK client with the connect timeout (1 second by default).
- `factory.setReadTimeout(properties.readTimeout())` sets the read timeout (2 seconds).
- `builder.baseUrl(...).requestFactory(factory).build()` finishes the client. Because the timeouts come from `ProviderProperties` and that record refuses zero or negative values, there is no way to configure a call that waits forever.

The call:

```java
Quote quote =
        quoteOf(
                restClient
                        .get()
                        .uri("/quotes/{sku}", sku)
                        .retrieve()
                        .body(QuoteBody.class));
result = "success";
```

`restClient.get().uri("/quotes/{sku}", sku).retrieve().body(QuoteBody.class)`: `uri` fills the `{sku}` placeholder, `retrieve()` sends the request and checks the status, `body(...)` reads the JSON into a record (see `json-jackson.md`).

The translation of what can go wrong into the domain's names:

```java
private static boolean isTimeout(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            return true;
        }
        if (cause instanceof IOException
                && String.valueOf(cause.getMessage()).contains("timed out")) {
            return true;
        }
    }
    return false;
}
```

It walks the chain of causes (`getCause()`) looking for an `HttpTimeoutException` or `SocketTimeoutException`. That is how "the provider was too slow" is told apart from "the provider could not be reached" (connection refused), a distinction that decides whether to retry (see `resilience-patterns.md`).

The "before" client of the benchmark has **no timeouts** on purpose:

```java
public RestClient unprotectedRestClient(
        RestClient.Builder builder, ProviderProperties properties) {
    return builder.baseUrl(properties.baseUrl())
            .requestFactory(new JdkClientHttpRequestFactory())
            .build();
}
```

## Try it yourself

1. With the stack up (`make up`) and the provider hanging (`curl -X PUT 'localhost:18103/admin/mode?mode=hang'`): `time curl -s -o /dev/null -w '%{http_code}\n' localhost:18104/price/sku-new` prints `504` after about 2 seconds, not the provider's 10.
2. `./mvnw test -pl price-service -Dtest=ProviderErrorsTest` shows each exception mapped to a domain failure.
3. `./mvnw verify -pl provider,price-service` runs `PriceServiceBehaviorIT`, which tests each failure mode.

## Mistakes people make

- No timeout at all: a hung provider hangs every caller.
- A read timeout longer than the caller's own deadline.
- Retrying a timeout: it adds load to a struggling provider.
- Catching one broad exception type and treating a timeout and a 503 alike.

## Check yourself

1. What are the two timeouts? *Connect (opening the connection) and read (waiting for the answer).*
2. What does a timeout look like in the exception? *`ResourceAccessException` caused by `HttpTimeoutException`.*
3. Which exception is a 503? *`HttpServerErrorException.ServiceUnavailable`.*
4. Why start from Boot's builder? *It brings metrics and tracing.*
5. Why validate the timeouts at startup? *A zero or negative value would mean waiting forever or failing at once.*

## Further reading

- Spring Framework 7.0.10, "REST Clients": https://docs.spring.io/spring-framework/reference/7.0.10/integration/rest-clients.html
- Spring Boot 4.1.2, calling REST services: https://docs.spring.io/spring-boot/4.1.2/reference/io/rest-client.html
