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
