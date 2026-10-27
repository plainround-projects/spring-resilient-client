# ArchUnit

## What it is

ArchUnit is a library that lets you write rules about the structure of your code as ordinary tests: "classes in `domain` may not depend on anything outside `domain`", "no field may be annotated `@Autowired`". It reads the compiled `.class` files, builds a model of who uses whom, and checks the rules against it.

## Why this repository uses it

The project rules say the layers are `domain`, `application`, `infrastructure` and `interfaces`, and that dependencies point inward: domain depends on nothing, application depends on domain, infrastructure and interfaces depend on both, and nothing depends on interfaces. Written in a document, a rule like that is forgotten. As a test, it fails the build the day someone breaks it.

## Where it appears

| Path | Role |
| --- | --- |
| `price-service/src/test/java/dev/plainround/price/ArchitectureTest.java` | the rules, plus a proxy rule for `@Retryable`, `@ConcurrencyLimit` and `@Transactional` |
| `pom.xml` | the `archunit-junit5` dependency, test scope |

## How it works underneath

1. `@AnalyzeClasses(packages = "...", importOptions = DoNotIncludeTests.class)` tells ArchUnit which compiled classes to import; here only production classes.
2. Each `@ArchTest static final ArchRule ...` field is one test. JUnit runs it through ArchUnit's engine.
3. A rule is built with a fluent API (`noClasses().that()...should()...`). When run, ArchUnit looks at every class, method, field and call, and reports each violation with the class and line.
4. A **layered architecture** rule names layers by package pattern (`..domain..`) and states who may access whom.
5. A custom `ArchCondition` can inspect method calls, which is how the proxy rules find a call from a class to a `@Transactional` method of the same class.

## Walkthrough of this repository's use

```java
static final ArchRule layersOnlyDependInward =
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain")
                .definedBy("..domain..")
                .layer("Application")
                .definedBy("..application..")
                .layer("Infrastructure")
                .definedBy("..infrastructure..")
                .layer("Interfaces")
                .definedBy("..interfaces..")
                .whereLayer("Interfaces")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Infrastructure")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Application")
                .mayOnlyBeAccessedByLayers("Interfaces")
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Interfaces");

@ArchTest
static final ArchRule domainDependsOnNothingButTheJdk =
```

The first rule is the layered rule. `consideringOnlyDependenciesInLayers()` ignores dependencies on libraries (Spring, the JDK). Four layers are defined by package. `Interfaces` and `Infrastructure` "may not be accessed by any layer": nothing in the project depends on them. `Application` may be accessed only by `Interfaces`. `Domain` may be accessed by the three outer layers.

```java
static final ArchRule domainDependsOnNothingButTheJdk =
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("..domain..", "java..");
```

The second rule is stricter than the first: a class in `domain` may depend only on `domain` and `java..` packages. No Spring, no JPA, no Jackson in the domain.

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

No field may carry `@Autowired`, `@Inject` or `@Resource`: constructor injection only.

```java
private static ArchCondition<JavaClass> callAProxiedMethodOfTheirOwnClass() {
    return new ArchCondition<>(
            "call a @Retryable, @ConcurrencyLimit or @Transactional method of their own"
                    + " class") {
        @Override
        public void check(JavaClass javaClass, ConditionEvents events) {
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                boolean proxied =
                        call.getTarget()
                                .resolveMember()
                                .map(
                                        method ->
                                                PROXIED_ANNOTATIONS.stream()
                                                        .anyMatch(method::isAnnotatedWith))
                                .orElse(false);
                if (call.getTargetOwner().equals(javaClass) && proxied) {
                    events.add(SimpleConditionEvent.satisfied(call, call.getDescription()));
                }
            }
        }
    };
}
```

The custom condition flags a call from a class to a method of its own class when that method carries `@Retryable`, `@ConcurrencyLimit` or `@Transactional`: the proxy that applies those annotations would be skipped.

**The test class that holds the ArchUnit rules, with its rule names:**

- `price-service/src/test/java/dev/plainround/price/ArchitectureTest.java`:
  - `layersOnlyDependInward`
  - `domainDependsOnNothingButTheJdk`
  - `applicationKnowsOnlyTheDomainTheJdkAndLogging`
  - `noFieldInjection`
  - `controllersLiveInTheInterfacesLayer`
  - `noPackageCycles`
  - `noSelfInvocationOfProxiedMethods`

## Try it yourself

1. `./mvnw test -Dtest=ArchitectureTest -pl price-service` runs the rules.
2. Break a rule on purpose: add `import org.springframework.stereotype.Component;` to a class in `domain/` and run the test again. It names the class and the dependency.
3. Add `@Autowired` to a field and run again.

## Mistakes people make

- Writing rules that match nothing (a typo in a package name): the rule passes while checking nothing. This repository keeps rules on real packages and breaks one to prove it works.
- Importing test classes into the analysis: tests are allowed to break rules on purpose (the traps do), so they are excluded.
- Using it instead of review: it catches structure, not logic.

## Check yourself

1. What does ArchUnit read? *The compiled classes.*
2. What does the layered rule forbid? *A layer using a layer it may not depend on.*
3. Why is `domain` stricter than the layered rule? *It forbids framework dependencies too.*
4. Why are test classes excluded? *The traps in tests break rules on purpose.*
5. What does the custom condition detect? *A call to a proxied method of the same class.*

## Further reading

- ArchUnit User Guide (version 1.5.1): https://www.archunit.org/userguide/html/000_Index.html
