package com.factoryflow.test.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.List;

public final class FactoryFlowRules {
    private static final List<String> SERVICES =
            List.of("gateway", "order", "inventory", "purchase", "production", "auth", "simulator");

    public static ArchRule noOtherServiceAccess(String me) {
        String[] others =
                SERVICES.stream()
                        .filter(s -> !s.equals(me))
                        .map(s -> "com.factoryflow." + s + "..")
                        .toArray(String[]::new);
        return noClasses()
                .that()
                .resideInAPackage("com.factoryflow." + me + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(others)
                .because("서비스 간 직접 참조 금지. 이벤트나 client/로 통신");
    }

    public static final ArchRule ENTRY_THROUGH_API =
            noClasses()
                    .that()
                    .haveSimpleNameEndingWith("Controller")
                    .or()
                    .haveSimpleNameEndingWith("Listener")
                    .should()
                    .dependOnClassesThat()
                    .haveSimpleNameEndingWith("Repository")
                    .because("진입점(Controller/Listener)은 service를 거친다");

    public static final ArchRule NO_DIRECT_PUBLISH =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.factoryflow.common.event..")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.amqp.rabbit.core.RabbitTemplate")
                    .because("발행은 EventPublisher(Outbox)로만");

    public static final ArchRule NO_FIELD_INJECTION =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    public static final ArchRule NO_STANDARD_STREAMS =
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    private FactoryFlowRules() {}
}
