# Spring Framework (beans, injection, proxies)

## What it is

Spring Framework is a library that creates and connects the objects of a Java program. Three ideas carry it.

- A **bean** is an object that Spring creates and keeps. The collection of all beans is the **application context**.
- **Dependency injection** (DI): a class does not create the objects it needs. It asks for them in its **constructor** (the method that builds the object), and Spring passes them in.
- A **proxy** is a stand-in object. Spring wraps some beans in a proxy that adds behavior (a database transaction, a retry, a limit) around the real method call.

## Why this repository uses it

The whole project is a Spring project. Constructor injection is a rule of the lesson: each class lists what it depends on in one constructor, so the dependencies are visible and a test can pass fakes. The proxy is also the subject of the lesson's hardest ideas: it decides when `@Transactional`, `@Retryable` and `@ConcurrencyLimit` apply, and when they silently do not.

## Where it appears

| Path | Role |
| --- | --- |
| `application/PriceService.java` | a `@Service` bean; its constructor takes the source, the cache and a clock |
| `infrastructure/ProviderCall.java` | a `@Component` whose method carries `@Retryable` and `@ConcurrencyLimit`; it is its own bean so the proxy applies |
| `infrastructure/ProviderClientConfig.java` | a `@Configuration` class with `@Bean` methods that build the `RestClient` |
| `infrastructure/ClockConfig.java` | a `@Configuration` class that creates the clock |

## How it works underneath

1. At startup Spring scans the packages under the class marked `@SpringBootApplication`, finds classes with a **stereotype annotation** (`@Component`, `@Service`, `@Repository`, `@RestController`, `@Configuration`) and registers each as a bean definition.
2. For each definition Spring works out the constructor, finds beans for each parameter by **type**, builds those first, and then builds the object. If a type has no bean, startup fails with a message that names it.
3. A class with exactly one constructor needs no `@Autowired`. This repository never uses `@Autowired` on fields, and a test (`ArchitectureTest`) fails if someone does.
4. `@Bean` on a method inside a `@Configuration` class means "the object this method returns is a bean". It is how the code registers objects it cannot annotate, such as `Clock.systemUTC()`.
5. When a bean's class has an annotation that needs a proxy, Spring builds the real object and then builds a **subclass at run time** (a CGLIB proxy) that overrides the annotated methods. Callers receive the proxy. A call from outside goes proxy → extra behavior → real method.
6. A call from one method of the real object to another method of **the same object** (`this.other()`) never touches the proxy. The extra behavior is skipped. This is the central trap of the second project.

## Walkthrough of this repository's use

```java
@Service
public class PriceService {

    private static final Logger LOG = LoggerFactory.getLogger(PriceService.class);

    private final QuoteSource source;
    private final QuoteCache cache;
    private final Clock clock;

    /**
     * Wires the service.
     *
     * @param source where fresh quotes come from
     * @param cache the last good quotes
     * @param clock the time source
     */
    public PriceService(QuoteSource source, QuoteCache cache, Clock clock) {
        this.source = source;
        this.cache = cache;
        this.clock = clock;
    }
```

The constructor takes `QuoteSource` and `QuoteCache` (interfaces) and a `Clock`. Spring finds `HttpQuoteSource`, `InMemoryQuoteCache` and the clock bean. When `price.protection=none`, other classes satisfy the same interfaces (see `resilience-patterns.md`); `PriceService` does not change.

```java
@Component
@ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)
public class ProviderCall {
```

`@ConditionalOnProperty` makes the bean exist only when `price.protection` is `full` (or missing). The retry and the limit are annotations on the method `attempt`; the annotations work only through the proxy, which is why `HttpQuoteSource` (another bean) calls `call.attempt(sku)` rather than a method of its own class.

**The proxy rule, in code.** The architecture test makes the rule checkable:

```java
static final ArchRule noFieldInjection =
        noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .orShould()
                .beAnnotatedWith("jakarta.inject.Inject")
                .orShould()
                .beAnnotatedWith("jakarta.annotation.Resource");
```

`ArchitectureTest` also contains a rule that fails if a class calls a `@Transactional`, `@Retryable` or `@ConcurrencyLimit` method of its own class.

## Try it yourself

1. Start the application and list the beans: run with `--debug` and search the log for `ProviderCall`.
2. Remove a bean annotation (for example `@Service` from one class) in a scratch copy and start: the error names the missing type.
3. Run `./mvnw test -Dtest=ArchitectureTest` to see the rules pass; add `@Autowired` to a field and run again to see one fail.

## Mistakes people make

- Calling `this.method()` and expecting an annotation on `method` to apply.
- Putting an annotation that needs a proxy on a `private` or `final` method: a subclass cannot override it.
- Creating a collaborator with `new` inside a class: it cannot be replaced in a test, and Spring cannot wrap it.
- Circular dependencies (A needs B, B needs A): constructor injection makes them fail at startup instead of at the first call.

## Check yourself

1. What is a bean? *An object Spring creates and keeps in the application context.*
2. Why does a class with one constructor need no `@Autowired`? *Spring uses the only constructor.*
3. What does a proxy add? *Behavior around a method call, such as a transaction.*
4. Why does `this.saveOrder()` skip a `@Transactional` on `saveOrder`? *The call does not go through the proxy.*
5. What does `@Bean` on a method do? *Registers the returned object as a bean.*

## Further reading

- Spring Framework 7.0.10 reference, "The IoC Container": https://docs.spring.io/spring-framework/reference/7.0.10/core/beans.html
- "Understanding AOP Proxies": https://docs.spring.io/spring-framework/reference/7.0.10/core/aop/proxying.html
