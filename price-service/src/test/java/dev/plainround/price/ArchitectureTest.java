package dev.plainround.price;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/** The dependency rule and the proxy rule, as code. */
@AnalyzeClasses(
        packages = "dev.plainround.price",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final List<String> PROXIED_ANNOTATIONS =
            List.of(
                    "org.springframework.resilience.annotation.Retryable",
                    "org.springframework.resilience.annotation.ConcurrencyLimit",
                    "org.springframework.transaction.annotation.Transactional");

    @ArchTest
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
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideOutsideOfPackages("..domain..", "java..");

    @ArchTest
    static final ArchRule applicationKnowsOnlyTheDomainTheJdkAndLogging =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideOutsideOfPackages(
                            "..domain..",
                            "..application..",
                            "java..",
                            "org.slf4j..",
                            "org.springframework.stereotype..");

    @ArchTest
    static final ArchRule noFieldInjection =
            noFields()
                    .should()
                    .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .orShould()
                    .beAnnotatedWith("jakarta.inject.Inject")
                    .orShould()
                    .beAnnotatedWith("jakarta.annotation.Resource");

    @ArchTest
    static final ArchRule controllersLiveInTheInterfacesLayer =
            classes()
                    .that()
                    .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .should()
                    .resideInAPackage("..interfaces..");

    @ArchTest
    static final ArchRule noPackageCycles =
            slices().matching("dev.plainround.price.(*)..").should().beFreeOfCycles();

    /**
     * {@code @Retryable} and {@code @ConcurrencyLimit} are applied by a proxy, which a call from
     * the same class skips.
     */
    @ArchTest
    static final ArchRule noSelfInvocationOfProxiedMethods =
            noClasses().should(callAProxiedMethodOfTheirOwnClass());

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
}
