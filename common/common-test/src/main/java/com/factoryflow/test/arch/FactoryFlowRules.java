package com.factoryflow.test.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CLAUDE.md 규칙 중 코드 구조로 확인할 수 있는 것. 서비스의 ArchitectureTest(본 코드)와 TestArchitectureTest(테스트 코드)가
 * 등록한다. 규칙 대상 클래스가 아직 없는 서비스는 archunit.properties 설정으로 통과한다.
 */
public final class FactoryFlowRules {
    private static final List<String> SERVICES =
            List.of("gateway", "order", "inventory", "purchase", "production", "auth", "simulator");

    private static final String REST_CONTROLLER =
            "org.springframework.web.bind.annotation.RestController";
    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String REQUEST_MAPPING =
            "org.springframework.web.bind.annotation.RequestMapping";
    private static final Set<String> HANDLER_MAPPINGS =
            Set.of(
                    REQUEST_MAPPING,
                    "org.springframework.web.bind.annotation.GetMapping",
                    "org.springframework.web.bind.annotation.PostMapping",
                    "org.springframework.web.bind.annotation.PutMapping",
                    "org.springframework.web.bind.annotation.PatchMapping",
                    "org.springframework.web.bind.annotation.DeleteMapping");
    private static final String REQUEST_BODY =
            "org.springframework.web.bind.annotation.RequestBody";
    private static final Set<String> SIMPLE_VALUE_PARAMS =
            Set.of(
                    "org.springframework.web.bind.annotation.RequestHeader",
                    "org.springframework.web.bind.annotation.RequestParam",
                    "org.springframework.web.bind.annotation.PathVariable");
    private static final String VALID = "jakarta.validation.Valid";
    private static final String VALIDATED = "org.springframework.validation.annotation.Validated";
    private static final String CONFIGURATION =
            "org.springframework.context.annotation.Configuration";
    private static final Set<String> SPRING_BEAN_ANNOTATIONS =
            Set.of(
                    "org.springframework.stereotype.Component",
                    "org.springframework.stereotype.Service",
                    "org.springframework.stereotype.Repository",
                    "org.springframework.stereotype.Controller",
                    "org.springframework.web.bind.annotation.RestController",
                    "org.springframework.web.bind.annotation.ControllerAdvice",
                    "org.springframework.web.bind.annotation.RestControllerAdvice",
                    "org.springframework.context.annotation.Configuration");
    private static final String LOMBOK_GENERATED = "lombok.Generated";
    private static final String AUTOWIRED =
            "org.springframework.beans.factory.annotation.Autowired";
    private static final String HTTP_EXCHANGE =
            "org.springframework.web.service.annotation.HttpExchange";
    private static final Set<String> EXCHANGE_ANNOTATIONS =
            Set.of(
                    HTTP_EXCHANGE,
                    "org.springframework.web.service.annotation.GetExchange",
                    "org.springframework.web.service.annotation.PostExchange",
                    "org.springframework.web.service.annotation.PutExchange",
                    "org.springframework.web.service.annotation.PatchExchange",
                    "org.springframework.web.service.annotation.DeleteExchange");
    private static final Set<String> QUERY_ANNOTATIONS =
            Set.of(
                    "org.springframework.data.jpa.repository.Query",
                    "org.springframework.data.jpa.repository.NativeQuery");
    private static final Set<String> ENTITY_MANAGER_QUERIES =
            Set.of(
                    "createQuery",
                    "createNativeQuery",
                    "createNamedQuery",
                    "createStoredProcedureQuery");
    private static final Set<String> PERSISTENCE_TYPES =
            Set.of(
                    "jakarta.persistence.Entity",
                    "jakarta.persistence.Embeddable",
                    "jakarta.persistence.MappedSuperclass");
    private static final String CONSTRAINT = "jakarta.validation.Constraint";
    private static final String CONSTRAINT_VALIDATOR = "jakarta.validation.ConstraintValidator";
    private static final String JPA_QUERY_FACTORY = "com.querydsl.jpa.impl.JPAQueryFactory";
    private static final String SERVICE = "org.springframework.stereotype.Service";
    private static final String HTTP_SERVICE_PROXY_FACTORY =
            "org.springframework.web.service.invoker.HttpServiceProxyFactory";
    private static final String CUSTOM_EXCEPTION =
            "com.factoryflow.common.web.exception.CustomException";
    private static final Set<String> CONTEXT_TESTS =
            Set.of(
                    "org.springframework.boot.test.context.SpringBootTest",
                    "org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest",
                    "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest");
    private static final String TEST_CONTAINERS_CONFIG =
            "com.factoryflow.test.container.TestContainersConfig";
    private static final String SPRING_TRANSACTIONAL =
            "org.springframework.transaction.annotation.Transactional";
    private static final String OPERATION = "io.swagger.v3.oas.annotations.Operation";
    private static final Set<String> API_RESPONSE_DOCS =
            Set.of(
                    "io.swagger.v3.oas.annotations.responses.ApiResponse",
                    "io.swagger.v3.oas.annotations.responses.ApiResponses");
    private static final String PARAMETER = "io.swagger.v3.oas.annotations.Parameter";
    private static final String SCHEMA = "io.swagger.v3.oas.annotations.media.Schema";
    private static final String DOMAIN_EVENT = "com.factoryflow.common.event.DomainEvent";
    private static final String ERROR_CODE =
            "com.factoryflow.common.web.exception.errorcode.ErrorCode";
    private static final Pattern DISABLING_ANNOTATION =
            Pattern.compile(
                    "(org\\.junit\\.jupiter\\.api\\.(condition\\.)?"
                            + "|org\\.springframework\\.test\\.context\\.junit\\.jupiter\\.)"
                            + "(Disabled|Enabled)\\w*");
    private static final DescribedPredicate<JavaAnnotation<?>> DISABLING =
            describe(
                    "@Disabled*, @Enabled* (JUnit, Spring)",
                    a -> DISABLING_ANNOTATION.matcher(a.getRawType().getName()).matches());
    private static final Set<String> ASSUMPTION_APIS =
            Set.of(
                    "org.junit.jupiter.api.Assumptions",
                    "org.assertj.core.api.Assumptions",
                    "org.assertj.core.api.BDDAssumptions",
                    "org.junit.Assume");
    private static final String TESTCONTAINERS = "org.testcontainers.junit.jupiter.Testcontainers";
    private static final Set<String> NO_EXAMPLE_TYPES =
            Set.of("java.util.List", "java.util.Set", "java.util.Collection", "java.util.Map");
    private static final Set<String> MOCK_ANNOTATIONS =
            Set.of(
                    "org.springframework.test.context.bean.override.mockito.MockitoBean",
                    "org.springframework.test.context.bean.override.mockito.MockitoSpyBean",
                    "org.springframework.boot.test.mock.mockito.MockBean",
                    "org.springframework.boot.test.mock.mockito.SpyBean",
                    "org.mockito.Mock",
                    "org.mockito.Spy");
    private static final Pattern CLIENT_PACKAGE =
            Pattern.compile("com\\.factoryflow\\.[^.]+\\.client(\\..*)?");

    private static final DescribedPredicate<JavaClass> RECORDS =
            describe("are records", FactoryFlowRules::isRecord);
    private static final DescribedPredicate<JavaClass> CONTROLLERS =
            describe(
                    "are controllers",
                    c -> c.isAnnotatedWith(REST_CONTROLLER) || c.isAnnotatedWith(CONTROLLER));
    private static final DescribedPredicate<JavaClass> DTOS =
            describe(
                    "are request or response DTOs",
                    c ->
                            c.getPackageName().matches(".*\\.dto\\.(request|response)(\\..*)?")
                                    && (c.getSimpleName().endsWith("Request")
                                            || c.getSimpleName().endsWith("Response")));
    private static final DescribedPredicate<JavaClass> API_DTOS =
            describe(
                    "are DTOs of our own API (not client/)",
                    c -> DTOS.test(c) && !isInClientPackage(c));
    private static final DescribedPredicate<JavaClass> API_RESPONSES =
            describe(
                    "are response DTOs of our own API (not client/)",
                    c -> API_DTOS.test(c) && c.getSimpleName().endsWith("Response"));

    // ---- 기존 규칙 ----

    public static final ArchRule NO_OTHER_SERVICE_ACCESS =
            classes()
                    .that(describe("belong to a service", c -> serviceOf(c) != null))
                    .should(
                            new ArchCondition<JavaClass>("not depend on another service") {
                                @Override
                                public void check(JavaClass c, ConditionEvents events) {
                                    String me = serviceOf(c);
                                    c.getDirectDependenciesFromSelf().stream()
                                            .filter(
                                                    d -> {
                                                        String other =
                                                                serviceOf(d.getTargetClass());
                                                        return other != null && !other.equals(me);
                                                    })
                                            .forEach(
                                                    d ->
                                                            events.add(
                                                                    SimpleConditionEvent.violated(
                                                                            d,
                                                                            d.getDescription())));
                                }
                            })
                    .because("서비스 간 직접 참조 금지. 이벤트나 client/로 통신");

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

    // ---- 주입, 로그 ----

    public static final ArchRule NO_METHOD_INJECTION =
            noMethods()
                    .should()
                    .beAnnotatedWith(AUTOWIRED)
                    .because("생성자 주입만 쓴다 (setter, 메서드 주입 금지)");

    public static final ArchRule CONSTRUCTOR_BY_LOMBOK =
            constructors()
                    .that()
                    .areDeclaredInClassesThat(
                            describe(
                                    "are Spring beans",
                                    c ->
                                            SPRING_BEAN_ANNOTATIONS.stream()
                                                    .anyMatch(c::isAnnotatedWith)))
                    .and(describe("have parameters", c -> !c.getRawParameterTypes().isEmpty()))
                    .should()
                    .beAnnotatedWith(LOMBOK_GENERATED)
                    .because(
                            "Bean 의 생성자는 @RequiredArgsConstructor 로 만든다 (직접 쓰지 않음, lombok.config 의"
                                    + " addLombokGeneratedAnnotation 필요)");

    public static final ArchRule SLF4J_ONLY =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "java.util.logging..",
                            "org.apache.commons.logging..",
                            "org.apache.logging.log4j..",
                            "org.apache.log4j..")
                    .because("로그는 Slf4j 로만 남긴다");

    // ---- 패키지 구조, 이름 ----

    public static final ArchRule LAYER_PACKAGES =
            CompositeArchRule.of(suffixInPackage("Controller", "..controller.."))
                    .and(suffixInPackage("Service", "..service.."))
                    .and(suffixInPackage("Repository", "..repository.."))
                    .and(suffixInPackage("Request", "..dto.request.."))
                    .and(suffixInPackage("Response", "..dto.response.."))
                    .and(suffixInPackage("ErrorCode", "..exception.."))
                    .and(suffixInPackage("EventListener", "..event.listener.."))
                    .and(suffixInPackage("Client", "com.factoryflow.*.client.*"))
                    .and(suffixInPackage("ClientConfig", "com.factoryflow.*.client.*"))
                    .because("CLAUDE.md 패키지 구조를 따른다");

    public static final ArchRule CONFIG_PACKAGE =
            classes()
                    .that(
                            describe(
                                    "are configuration classes other than {상대 도메인}ClientConfig",
                                    c ->
                                            (c.isAnnotatedWith(CONFIGURATION)
                                                            || c.getSimpleName().endsWith("Config"))
                                                    && !c.getSimpleName().endsWith("ClientConfig")))
                    .should()
                    .resideInAPackage("..config..")
                    .because(
                            "설정은 config/ 에 둔다. 다른 서비스 호출 설정({상대 도메인}ClientConfig)은"
                                    + " client/{상대 서비스}/ 에 둔다");

    public static final ArchRule ENTITIES_IN_DOMAIN =
            classes()
                    .that(
                            describe(
                                    "are JPA entities, embeddables or mapped superclasses of a service",
                                    c ->
                                            serviceOf(c) != null
                                                    && PERSISTENCE_TYPES.stream()
                                                            .anyMatch(c::isAnnotatedWith)))
                    .should()
                    .resideInAPackage("..domain..")
                    .because("엔티티는 domain/ 에 둔다");

    public static final ArchRule CONSTRAINTS_IN_DTO_REQUEST =
            classes()
                    .that(
                            describe(
                                    "are custom constraints or their validators",
                                    c ->
                                            serviceOf(c) != null
                                                    && (c.isAnnotatedWith(CONSTRAINT)
                                                            || c.isAssignableTo(
                                                                    CONSTRAINT_VALIDATOR))))
                    .should()
                    .resideInAPackage("..dto.request..")
                    .because("요청 전용 커스텀 제약은 dto/request 에 둔다");

    public static final ArchRule QUERYDSL_LAYOUT =
            CompositeArchRule.of(
                            classes()
                                    .that(
                                            describe(
                                                    "use JPAQueryFactory",
                                                    c ->
                                                            serviceOf(c) != null
                                                                    && c
                                                                            .getDirectDependenciesFromSelf()
                                                                            .stream()
                                                                            .anyMatch(
                                                                                    d ->
                                                                                            d.getTargetClass()
                                                                                                    .getName()
                                                                                                    .equals(
                                                                                                            JPA_QUERY_FACTORY))))
                                    .should()
                                    .haveSimpleNameEndingWith("RepositoryImpl")
                                    .andShould()
                                    .resideInAPackage("..repository.."))
                    .and(
                            classes()
                                    .that()
                                    .haveSimpleNameEndingWith("RepositoryImpl")
                                    .should(
                                            check(
                                                    "implement {Entity}RepositoryCustom of the same name",
                                                    c ->
                                                            c.getRawInterfaces().stream()
                                                                    .anyMatch(
                                                                            i ->
                                                                                    i.getSimpleName()
                                                                                            .equals(
                                                                                                    c.getSimpleName()
                                                                                                                    .replace(
                                                                                                                            "RepositoryImpl",
                                                                                                                            "")
                                                                                                            + "RepositoryCustom")),
                                                    JavaClass::getName)))
                    .and(
                            classes()
                                    .that()
                                    .haveSimpleNameEndingWith("RepositoryCustom")
                                    .should()
                                    .beInterfaces()
                                    .andShould()
                                    .resideInAPackage("..repository.."))
                    .because(
                            "QueryDSL 은 repository/{엔티티}RepositoryCustom(인터페이스) +"
                                    + " {엔티티}RepositoryImpl 에서만 쓴다");

    public static final ArchRule REPOSITORY_NAMED_BY_ENTITY =
            classes()
                    .that(describe("are Spring Data repositories", c -> entityOf(c) != null))
                    .should(
                            check(
                                    "be named {Entity}Repository",
                                    c -> c.getSimpleName().equals(entityOf(c) + "Repository"),
                                    JavaClass::getName))
                    .because("Repository 는 {엔티티}Repository");

    public static final ArchRule SERVICE_NAMING =
            classes()
                    .that(
                            describe(
                                    "are @Service beans of a service",
                                    c -> serviceOf(c) != null && c.isAnnotatedWith(SERVICE)))
                    .should()
                    .haveSimpleNameEndingWith("Service")
                    .because("Service 는 {도메인}Service");

    public static final ArchRule CLIENT_CONFIG_USES_PROXY_FACTORY =
            classes()
                    .that()
                    .haveSimpleNameEndingWith("ClientConfig")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName(HTTP_SERVICE_PROXY_FACTORY)
                    .because(
                            "{상대 도메인}Client 의 구현은 {상대 도메인}ClientConfig 가 HttpServiceProxyFactory 로 만든다");

    public static final ArchRule INTERNAL_API_RESPONSE_NAMING =
            methods()
                    .that()
                    .areDeclaredInClassesThat(
                            describe(
                                    "are internal API controllers",
                                    c ->
                                            CONTROLLERS.test(c)
                                                    && c.getSimpleName().startsWith("Internal")))
                    .and(describe("are request handlers", FactoryFlowRules::isHandler))
                    .should(
                            check(
                                    "return only Internal*Response DTOs",
                                    m ->
                                            typesIn(m.getReturnType())
                                                    .filter(API_RESPONSES)
                                                    .allMatch(
                                                            c ->
                                                                    c.getSimpleName()
                                                                            .startsWith(
                                                                                    "Internal")),
                                    JavaMethod::getFullName))
                    .because("내부 API 응답은 Internal{도메인}Response");

    public static final ArchRule FACTORY_METHOD_ARITY =
            methods()
                    .that()
                    .areDeclaredInClassesThat(API_RESPONSES)
                    .and()
                    .areStatic()
                    .and(
                            describe(
                                    "are named from or of",
                                    m -> m.getName().equals("from") || m.getName().equals("of")))
                    .should(
                            check(
                                    "take one source in from(...) and several values in of(...)",
                                    m ->
                                            m.getName().equals("from")
                                                    ? m.getRawParameterTypes().size() == 1
                                                    : m.getRawParameterTypes().size() >= 2,
                                    JavaMethod::getFullName))
                    .because("응답 DTO 는 도메인 객체 하나를 바꿀 때 from(원본), 값 여러 개로 만들 때 of(...)");

    public static final ArchRule SERVICE_THROWS_CUSTOM_EXCEPTION =
            noClasses()
                    .that()
                    .resideInAPackage("..service..")
                    .should()
                    .callConstructorWhere(
                            describe(
                                    "the target is a RuntimeException other than CustomException",
                                    (JavaConstructorCall call) ->
                                            call.getTargetOwner()
                                                            .isAssignableTo(RuntimeException.class)
                                                    && !call.getTargetOwner()
                                                            .getName()
                                                            .equals(CUSTOM_EXCEPTION)))
                    .because("service 의 예외는 {서비스}ErrorCode 를 담은 CustomException 으로 던진다");

    // ---- 트랜잭션 ----

    public static final ArchRule TRANSACTIONAL_ANNOTATION_ONLY =
            CompositeArchRule.of(
                            noClasses()
                                    .that()
                                    .resideInAPackage("com.factoryflow..")
                                    .should()
                                    .dependOnClassesThat()
                                    .haveFullyQualifiedName(
                                            "org.springframework.transaction.support.TransactionTemplate"))
                    .and(
                            noClasses()
                                    .should()
                                    .dependOnClassesThat()
                                    .haveFullyQualifiedName("jakarta.transaction.Transactional"))
                    .because(
                            "트랜잭션은 Spring @Transactional 로 건다 (TransactionTemplate,"
                                    + " jakarta.transaction.Transactional 쓰지 않음)");

    public static final ArchRule TRANSACTIONAL_IN_SERVICE_LAYER =
            classes()
                    .that(describe("use @Transactional", FactoryFlowRules::usesTransactional))
                    .should()
                    .resideInAPackage("..service..")
                    .because("트랜잭션 경계는 service 계층에만 둔다");

    public static final ArchRule TRANSACTIONAL_METHODS_PROXYABLE =
            methods()
                    .that()
                    .areAnnotatedWith(SPRING_TRANSACTIONAL)
                    .should(
                            check(
                                    "be overridable by the proxy (not private, final or static)",
                                    m ->
                                            !m.getModifiers().contains(JavaModifier.PRIVATE)
                                                    && !m.getModifiers()
                                                            .contains(JavaModifier.FINAL)
                                                    && !m.getModifiers()
                                                            .contains(JavaModifier.STATIC),
                                    JavaMethod::getFullName))
                    .because("private, final, static 메서드의 @Transactional 은 프록시가 가로채지 못해 무시된다");

    public static final ArchRule NO_SELF_CALL_TO_TRANSACTIONAL =
            noClasses()
                    .should()
                    .callMethodWhere(
                            describe(
                                    "the target is an @Transactional method of the same class",
                                    (JavaMethodCall call) ->
                                            call.getOriginOwner().equals(call.getTargetOwner())
                                                    && call.getTarget().resolveMember().stream()
                                                            .anyMatch(
                                                                    m ->
                                                                            m.isAnnotatedWith(
                                                                                    SPRING_TRANSACTIONAL))))
                    .because(
                            "같은 클래스 안에서 부르면 프록시를 거치지 않아 @Transactional 이 무시된다. 다른"
                                    + " Bean(예: {도메인}Writer) 으로 뺀다");

    public static final ArchRule NO_CLIENT_CALL_IN_TRANSACTION =
            methods()
                    .that()
                    .areAnnotatedWith(SPRING_TRANSACTIONAL)
                    .should(
                            check(
                                    "not call another service through client/ inside the transaction",
                                    m ->
                                            m.getMethodCallsFromSelf().stream()
                                                    .noneMatch(
                                                            call ->
                                                                    isInClientPackage(
                                                                            call.getTargetOwner())),
                                    JavaMethod::getFullName))
                    .because(
                            "다른 서비스 호출을 트랜잭션 안에서 하면 응답을 기다리는 동안 DB 커넥션을 잡는다. 호출은"
                                    + " 트랜잭션 밖에서 끝내고 저장만 트랜잭션으로 한다");

    public static final ArchRule CONTROLLER_NAMING =
            classes()
                    .that(CONTROLLERS)
                    .should()
                    .haveSimpleNameEndingWith("Controller")
                    .andShould(
                            check(
                                    "be named Internal*Controller exactly when mapped under"
                                            + " /internal",
                                    c ->
                                            mappedPaths(c).stream()
                                                            .anyMatch(
                                                                    p -> p.startsWith("/internal"))
                                                    == c.getSimpleName().startsWith("Internal"),
                                    JavaClass::getName))
                    .because("내부 API(/internal/**)는 Internal{도메인}Controller, 나머지는 {도메인}Controller");

    public static final ArchRule ERROR_CODE_NAMING =
            classes()
                    .that()
                    .implement(ERROR_CODE)
                    .should(
                            check(
                                    "be exception/{Service}ErrorCode of its own service",
                                    c ->
                                            serviceOf(c) != null
                                                    && c.getPackageName().endsWith(".exception")
                                                    && c.getSimpleName()
                                                            .equals(
                                                                    capitalize(serviceOf(c))
                                                                            + "ErrorCode"),
                                    JavaClass::getName))
                    .because("에러 코드는 exception/{서비스}ErrorCode");

    public static final ArchRule EVENT_LISTENER_NAMING =
            classes()
                    .that()
                    .resideInAPackage("..event.listener..")
                    .and()
                    .areTopLevelClasses()
                    .and()
                    .doNotHaveSimpleName("package-info")
                    .and(not(RECORDS))
                    .should()
                    .haveNameMatching(
                            SERVICES.stream()
                                    .map(FactoryFlowRules::capitalize)
                                    .collect(Collectors.joining("|", ".*\\.(", ")EventListener")))
                    .because(
                            "수신은 event/listener 의 {발행 서비스}EventListener."
                                    + " 받은 이벤트 payload record 도 event/listener 에 둔다");

    // ---- 요청 검증 ----

    public static final ArchRule NO_VALIDATED_ON_CONTROLLER =
            noClasses()
                    .that(CONTROLLERS)
                    .should()
                    .beAnnotatedWith(VALIDATED)
                    .because(
                            "@RequestParam/@PathVariable 검증은 Spring 내장 검증으로 한다 (@Validated 붙이지"
                                    + " 않음)");

    public static final ArchRule VALID_REQUEST_BODY =
            methods()
                    .that()
                    .areDeclaredInClassesThat(CONTROLLERS)
                    .should(
                            check(
                                    "put @Valid on every @RequestBody parameter",
                                    m ->
                                            m.getParameters().stream()
                                                    .filter(p -> p.isAnnotatedWith(REQUEST_BODY))
                                                    .allMatch(p -> p.isAnnotatedWith(VALID)),
                                    JavaMethod::getFullName))
                    .because("@RequestBody DTO 는 @Valid 로 검증한다");

    // ---- DTO ----

    public static final ArchRule DTOS_ARE_RECORDS =
            classes().that(DTOS).should(beRecords()).because("DTO(요청, 응답, 내부 API 응답)는 모두 record");

    public static final ArchRule RESPONSE_NAMING =
            classes()
                    .that()
                    .resideInAPackage("..dto.response..")
                    .and()
                    .areTopLevelClasses()
                    .and()
                    .doNotHaveSimpleName("package-info")
                    .should()
                    .haveSimpleNameEndingWith("Response")
                    .because("응답 DTO 는 모두 Response 로 끝난다 (client 응답 포함)");

    public static final ArchRule NO_NESTED_CLASSES =
            classes()
                    .should(
                            check(
                                    "not be declared inside another class (member or local)",
                                    c -> !c.isMemberClass() && !c.isLocalClass(),
                                    JavaClass::getName))
                    .because("클래스 안에 클래스(record, enum, interface 포함)를 두지 않는다. 별도 파일로 뺀다");

    public static final ArchRule RESPONSE_STATIC_FACTORY =
            classes()
                    .that(API_RESPONSES)
                    .should(
                            check(
                                    "have a public static from(...) or of(...) returning itself",
                                    FactoryFlowRules::hasStaticFactory,
                                    JavaClass::getName))
                    .because("응답 DTO 는 정적 팩토리 from(원본) / of(...) 로 만든다");

    public static final ArchRule NO_RESPONSE_CONSTRUCTOR_OUTSIDE =
            noClasses()
                    .that(not(API_RESPONSES))
                    .should()
                    .callConstructorWhere(
                            describe(
                                    "the target is a response DTO of our own API",
                                    (JavaConstructorCall call) ->
                                            API_RESPONSES.test(call.getTargetOwner())))
                    .because("응답 DTO 는 밖에서 new 로 만들지 않는다 (client 응답 DTO 는 제외)");

    // ---- client ----

    public static final ArchRule CLIENT_IS_HTTP_INTERFACE =
            classes()
                    .that()
                    .haveSimpleNameEndingWith("Client")
                    .and()
                    .resideInAPackage("..client..")
                    .should()
                    .beInterfaces()
                    .andShould()
                    .beAnnotatedWith(HTTP_EXCHANGE)
                    .because("{상대 도메인}Client 는 HTTP Interface(@HttpExchange) 로 선언한다");

    public static final ArchRule NO_REST_TEMPLATE_OR_FEIGN =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
                    .orShould()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.cloud.openfeign..", "feign..")
                    .because("동기 호출은 HTTP Interface 로 한다 (OpenFeign, RestTemplate 쓰지 않음)");

    public static final ArchRule CLIENT_EXCHANGE_ONLY_FROM_CLIENT =
            noClasses()
                    .should()
                    .callMethodWhere(
                            describe(
                                    "the target is an HTTP exchange method of another type",
                                    (JavaMethodCall call) ->
                                            !call.getOriginOwner().equals(call.getTargetOwner())
                                                    && call.getTarget().resolveMember().stream()
                                                            .anyMatch(
                                                                    m ->
                                                                            EXCHANGE_ANNOTATIONS
                                                                                    .stream()
                                                                                    .anyMatch(
                                                                                            m
                                                                                                    ::isAnnotatedWith))))
                    .because(
                            "HTTP 선언 메서드는 client 의 default 메서드에서만 부른다 (실패를 {상대"
                                    + " 서비스}_UNAVAILABLE 로 바꾸기 위해)");

    // ---- 데이터 접근 ----

    public static final ArchRule QUERYDSL_FOR_CUSTOM_QUERIES =
            CompositeArchRule.of(
                            noMethods()
                                    .should()
                                    .beAnnotatedWith(
                                            describe(
                                                    "@Query or @NativeQuery",
                                                    (JavaAnnotation<?> a) ->
                                                            QUERY_ANNOTATIONS.contains(
                                                                    a.getRawType().getName()))))
                    .and(
                            noClasses()
                                    .should()
                                    .callMethodWhere(
                                            describe(
                                                    "the target is EntityManager.create*Query",
                                                    (JavaMethodCall call) ->
                                                            call.getTargetOwner()
                                                                            .getName()
                                                                            .equals(
                                                                                    "jakarta.persistence.EntityManager")
                                                                    && ENTITY_MANAGER_QUERIES
                                                                            .contains(
                                                                                    call
                                                                                            .getName()))))
                    .because(
                            "쿼리는 Spring Data 자동 쿼리 메서드로 만들고, 그걸로 안 되면 QueryDSL"
                                    + " ({엔티티}RepositoryCustom + {엔티티}RepositoryImpl) 로 만든다");

    public static final ArchRule NO_JDBC =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
                    .because("DB 접근은 JPA(Spring Data, QueryDSL) 로만 한다. JDBC 를 쓰지 않는다");

    // ---- 컨트롤러 ----

    public static final ArchRule CONTROLLER_STORES_SERVICE_RESULT =
            classes()
                    .that(CONTROLLERS)
                    .should(
                            new ArchCondition<JavaClass>(
                                    "store service call results in a local variable before using them") {
                                @Override
                                public void check(JavaClass controller, ConditionEvents events) {
                                    for (String violation : inlineServiceCalls(controller)) {
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        controller, violation));
                                    }
                                }
                            })
                    .because(
                            "service 호출 결과를 ApiResponse.ok(...) 같은 다른 호출 안에 바로 넣지 않는다."
                                    + " 지역 변수에 먼저 담는다");

    // ---- 메서드 길이 ----

    static final int MAX_METHOD_LINES = 30;

    public static final ArchRule METHOD_LENGTH =
            classes()
                    .that(describe("belong to a service", c -> serviceOf(c) != null))
                    .should(
                            new ArchCondition<JavaClass>(
                                    "keep methods at most " + MAX_METHOD_LINES + " lines") {
                                @Override
                                public void check(JavaClass c, ConditionEvents events) {
                                    for (String violation : longMethods(c)) {
                                        events.add(SimpleConditionEvent.violated(c, violation));
                                    }
                                }
                            })
                    .because("메서드는 30줄 이하. 길면 단계별 private 메서드로 나눈다");

    // ---- 이벤트 ----

    public static final ArchRule EVENTS_ARE_PUBLISHER_RECORDS =
            classes()
                    .that()
                    .implement(DOMAIN_EVENT)
                    .should()
                    .resideInAPackage("..event.publisher")
                    .andShould(beRecords())
                    .because("발행 payload 는 event/publisher 의 record");

    // ---- Swagger ----

    public static final ArchRule API_DOCUMENTED =
            methods()
                    .that()
                    .areDeclaredInClassesThat(CONTROLLERS)
                    .and(describe("are request handlers", FactoryFlowRules::isHandler))
                    .should(
                            check(
                                    "have @Operation and @ApiResponse(s)",
                                    m ->
                                            m.isAnnotatedWith(OPERATION)
                                                    && API_RESPONSE_DOCS.stream()
                                                            .anyMatch(m::isAnnotatedWith),
                                    JavaMethod::getFullName))
                    .andShould(
                            check(
                                    "put @Parameter on every header, query and path parameter",
                                    m ->
                                            m.getParameters().stream()
                                                    .filter(FactoryFlowRules::isSimpleValueParam)
                                                    .allMatch(p -> p.isAnnotatedWith(PARAMETER)),
                                    JavaMethod::getFullName))
                    .andShould(
                            check(
                                    "fill summary and description of @Operation",
                                    m ->
                                            m.tryGetAnnotationOfType(OPERATION)
                                                    .map(
                                                            a ->
                                                                    hasText(a, "summary")
                                                                            && hasText(
                                                                                    a,
                                                                                    "description"))
                                                    .orElse(true),
                                    JavaMethod::getFullName))
                    .andShould(
                            check(
                                    "fill responseCode and description of every @ApiResponse",
                                    m ->
                                            apiResponses(m)
                                                    .allMatch(
                                                            a ->
                                                                    hasText(a, "responseCode")
                                                                            && hasText(
                                                                                    a,
                                                                                    "description")),
                                    JavaMethod::getFullName))
                    .andShould(
                            check(
                                    "fill description and example of every @Parameter",
                                    m ->
                                            m.getParameters().stream()
                                                    .filter(FactoryFlowRules::isSimpleValueParam)
                                                    .map(p -> p.tryGetAnnotationOfType(PARAMETER))
                                                    .flatMap(java.util.Optional::stream)
                                                    .allMatch(
                                                            a ->
                                                                    hasText(a, "description")
                                                                            && hasText(
                                                                                    a, "example")),
                                    JavaMethod::getFullName))
                    .because("문서 없는 API 는 머지하지 않는다");

    public static final ArchRule DTO_FIELDS_DOCUMENTED =
            fields().that()
                    .areDeclaredInClassesThat(API_DTOS)
                    .and()
                    .areNotStatic()
                    .should()
                    .beAnnotatedWith(SCHEMA)
                    .andShould(
                            check(
                                    "fill description of @Schema, and example unless the field is a"
                                            + " list, map, array or record",
                                    f ->
                                            f.tryGetAnnotationOfType(SCHEMA)
                                                    .map(
                                                            a ->
                                                                    hasText(a, "description")
                                                                            && (!needsExample(
                                                                                            f
                                                                                                    .getRawType())
                                                                                    || hasText(
                                                                                            a,
                                                                                            "example")))
                                                    .orElse(true),
                                    JavaField::getFullName))
                    .because("요청/응답 DTO 필드는 @Schema(description, example) 로 문서화한다");

    // ---- 테스트 코드 (ImportOption.OnlyIncludeTests 로 등록) ----

    public static final ArchRule NO_SKIPPED_TESTS =
            CompositeArchRule.of(noClasses().should().beAnnotatedWith(DISABLING))
                    .and(noMethods().should().beAnnotatedWith(DISABLING))
                    .and(
                            noClasses()
                                    .should()
                                    .dependOnClassesThat(
                                            describe(
                                                    "are assumption APIs (Assumptions, Assume)",
                                                    c -> ASSUMPTION_APIS.contains(c.getName()))))
                    .and(
                            noClasses()
                                    .should()
                                    .beAnnotatedWith(
                                            describe(
                                                    "@Testcontainers(disabledWithoutDocker = true)",
                                                    a ->
                                                            a.getRawType()
                                                                            .getName()
                                                                            .equals(TESTCONTAINERS)
                                                                    && Boolean.TRUE.equals(
                                                                            a.get(
                                                                                            "disabledWithoutDocker")
                                                                                    .orElse(
                                                                                            false)))))
                    .because(
                            "테스트를 끄거나 건너뛰지 않는다 (@Disabled*, @Enabled*, Assumptions,"
                                    + " disabledWithoutDocker)");

    public static final ArchRule TESTS_USE_TESTCONTAINERS =
            CompositeArchRule.of(
                            classes()
                                    .that(
                                            describe(
                                                    "are @SpringBootTest or @DataJpaTest",
                                                    c ->
                                                            CONTEXT_TESTS.stream()
                                                                    .anyMatch(c::isAnnotatedWith)))
                                    .should(
                                            check(
                                                    "import TestContainersConfig",
                                                    FactoryFlowRules::importsTestContainers,
                                                    JavaClass::getName)))
                    .and(
                            noClasses()
                                    .should()
                                    .dependOnClassesThat()
                                    .resideInAnyPackage(
                                            "org.h2..", "org.hsqldb..", "org.apache.derby.."))
                    .because("테스트의 DB, MQ, Redis 는 Testcontainers (TestContainersConfig) 로 띄운다");

    public static final ArchRule MOCK_ONLY_CLIENT_AND_CLOCK =
            fields().that(
                            describe(
                                    "are mocks",
                                    (JavaField f) ->
                                            MOCK_ANNOTATIONS.stream().anyMatch(f::isAnnotatedWith)))
                    .should(
                            check(
                                    "mock only client/ or Clock",
                                    f ->
                                            isInClientPackage(f.getRawType())
                                                    || f.getRawType()
                                                            .getName()
                                                            .equals("java.time.Clock"),
                                    JavaField::getFullName))
                    .because("우리 코드(Repository, Service, EventPublisher)는 mock 하지 않는다");

    public static final ArchRule NO_INLINE_MOCK_OF_OUR_CODE =
            codeUnits()
                    .should(
                            check(
                                    "not pass Repository, Service or EventPublisher to Mockito.mock/spy",
                                    cu ->
                                            !callsMockitoMockOrSpy(cu)
                                                    || cu.getReferencedClassObjects().stream()
                                                            .noneMatch(
                                                                    r -> isOurCode(r.getRawType())),
                                    JavaCodeUnit::getFullName))
                    .because("우리 코드(Repository, Service, EventPublisher)는 mock 하지 않는다");

    // ---- helpers ----

    private static ArchRule suffixInPackage(String suffix, String packageIdentifier) {
        return classes()
                .that()
                .haveSimpleNameEndingWith(suffix)
                .and()
                .areTopLevelClasses()
                .should()
                .resideInAPackage(packageIdentifier);
    }

    private static ArchCondition<JavaClass> beRecords() {
        return check("be records", FactoryFlowRules::isRecord, JavaClass::getName);
    }

    private static <T> ArchCondition<T> check(
            String description, Predicate<? super T> ok, Function<? super T, String> name) {
        return new ArchCondition<>(description) {
            @Override
            public void check(T item, ConditionEvents events) {
                if (!ok.test(item)) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    item, name.apply(item) + " violates: " + description));
                }
            }
        };
    }

    private static boolean isRecord(JavaClass c) {
        return c.getRawSuperclass().map(s -> s.getName().equals("java.lang.Record")).orElse(false);
    }

    private static boolean hasText(JavaAnnotation<?> annotation, String attribute) {
        return annotation.get(attribute).map(v -> !String.valueOf(v).isBlank()).orElse(false);
    }

    private static Stream<JavaAnnotation<?>> apiResponses(JavaMethod m) {
        Stream<JavaAnnotation<?>> single =
                m
                        .tryGetAnnotationOfType(
                                "io.swagger.v3.oas.annotations.responses.ApiResponse")
                        .stream()
                        .map(a -> (JavaAnnotation<?>) a);
        Stream<JavaAnnotation<?>> container =
                m
                        .tryGetAnnotationOfType(
                                "io.swagger.v3.oas.annotations.responses.ApiResponses")
                        .flatMap(a -> a.get("value"))
                        .stream()
                        .flatMap(
                                v ->
                                        v instanceof Object[] array
                                                ? Arrays.stream(array)
                                                : Stream.of(v))
                        .filter(JavaAnnotation.class::isInstance)
                        .map(v -> (JavaAnnotation<?>) v);
        return Stream.concat(single, container);
    }

    private static boolean needsExample(JavaClass type) {
        return !(type.isArray() || NO_EXAMPLE_TYPES.contains(type.getName()) || isRecord(type));
    }

    private static boolean callsMockitoMockOrSpy(JavaCodeUnit cu) {
        return cu.getMethodCallsFromSelf().stream()
                .anyMatch(
                        call ->
                                call.getTargetOwner().getName().equals("org.mockito.Mockito")
                                        && (call.getName().equals("mock")
                                                || call.getName().equals("spy")));
    }

    private static boolean isOurCode(JavaClass c) {
        return c.getName().startsWith("com.factoryflow.")
                && !isInClientPackage(c)
                && (c.getSimpleName().endsWith("Repository")
                        || c.getSimpleName().endsWith("Service")
                        || c.getName().equals("com.factoryflow.common.event.EventPublisher"));
    }

    /**
     * class 파일의 줄 번호로 메서드의 첫 줄부터 마지막 줄까지 센다. 생성자, static 초기화, 브리지 메서드는 뺀다. 람다 본문은 javac 가 따로 만든
     * synthetic 메서드(lambda${감싼 메서드}$n)에 들어가므로, 그 줄 범위를 감싼 메서드에 합쳐 소스에서 보이는 길이로 센다.
     */
    private static List<String> longMethods(JavaClass c) {
        List<String> methodNames = new ArrayList<>();
        List<int[]> methodLines = new ArrayList<>();
        List<String> lambdaOwners = new ArrayList<>();
        List<int[]> lambdaLines = new ArrayList<>();
        URI source =
                c.getSource()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                c.getName() + " 의 class 파일을 찾을 수 없다"))
                        .getUri();
        try (InputStream in = source.toURL().openStream()) {
            new ClassReader(in)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    boolean synthetic = (access & Opcodes.ACC_SYNTHETIC) != 0;
                                    boolean lambda = synthetic && name.startsWith("lambda$");
                                    if (name.startsWith("<")
                                            || (access & Opcodes.ACC_BRIDGE) != 0
                                            || (synthetic && !lambda)) {
                                        return null;
                                    }
                                    int[] lines = {Integer.MAX_VALUE, Integer.MIN_VALUE};
                                    if (lambda) {
                                        lambdaOwners.add(name.split("\\$")[1]);
                                        lambdaLines.add(lines);
                                    } else {
                                        methodNames.add(name);
                                        methodLines.add(lines);
                                    }
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitLineNumber(int line, Label start) {
                                            lines[0] = Math.min(lines[0], line);
                                            lines[1] = Math.max(lines[1], line);
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // 람다는 이름이 같고 람다보다 앞에서 시작하는 메서드 중 가장 가까운 것에 붙인다 (오버로드 구분)
        for (int k = 0; k < lambdaOwners.size(); k++) {
            int[] lambda = lambdaLines.get(k);
            int owner = -1;
            for (int m = 0; m < methodNames.size(); m++) {
                int[] method = methodLines.get(m);
                if (methodNames.get(m).equals(lambdaOwners.get(k))
                        && method[0] <= lambda[0]
                        && (owner < 0 || method[0] > methodLines.get(owner)[0])) {
                    owner = m;
                }
            }
            if (owner >= 0) {
                methodLines.get(owner)[1] = Math.max(methodLines.get(owner)[1], lambda[1]);
            }
        }
        List<String> violations = new ArrayList<>();
        for (int m = 0; m < methodNames.size(); m++) {
            int[] method = methodLines.get(m);
            int lines = method[1] - method[0] + 1;
            if (method[0] <= method[1] && lines > MAX_METHOD_LINES) {
                violations.add(
                        c.getSimpleName()
                                + "."
                                + methodNames.get(m)
                                + " 는 "
                                + lines
                                + "줄이다 (최대 "
                                + MAX_METHOD_LINES
                                + "줄)");
            }
        }
        return violations;
    }

    // service 메서드 호출 바로 다음 명령이 지역 변수 저장, 반환, 버리기가 아니면 다른 식 안에서 바로 쓴 것이다
    private static List<String> inlineServiceCalls(JavaClass controller) {
        List<String> violations = new ArrayList<>();
        URI source =
                controller
                        .getSource()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                controller.getName() + " 의 class 파일을 찾을 수 없다"))
                        .getUri();
        try (InputStream in = source.toURL().openStream()) {
            new ClassReader(in)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    return new InlineCallFinder(
                                            controller.getName(), name, violations);
                                }
                            },
                            ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return violations;
    }

    private static final class InlineCallFinder extends MethodVisitor {
        private final String owner;
        private final String method;
        private final List<String> violations;
        private String pendingCall;
        private int line;

        InlineCallFinder(String owner, String method, List<String> violations) {
            super(Opcodes.ASM9);
            this.owner = owner;
            this.method = method;
            this.violations = violations;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            this.line = line;
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                pendingCall = null;
            } else {
                usedInline();
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode != Opcodes.CHECKCAST) {
                usedInline();
            }
        }

        @Override
        public void visitInsn(int opcode) {
            boolean returnedOrDropped =
                    (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
                            || opcode == Opcodes.POP
                            || opcode == Opcodes.POP2;
            if (returnedOrDropped) {
                pendingCall = null;
            } else {
                usedInline();
            }
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            usedInline();
            String className = owner.replace('/', '.');
            if (className.contains(".service.")
                    && className.endsWith("Service")
                    && !descriptor.endsWith(")V")) {
                pendingCall = className.substring(className.lastIndexOf('.') + 1) + "." + name;
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            usedInline();
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            usedInline();
        }

        @Override
        public void visitLdcInsn(Object value) {
            usedInline();
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            usedInline();
        }

        private void usedInline() {
            if (pendingCall != null) {
                violations.add(
                        owner
                                + "."
                                + method
                                + " (line "
                                + line
                                + ") 이 "
                                + pendingCall
                                + " 결과를 변수에 담지 않고 바로 쓴다");
                pendingCall = null;
            }
        }
    }

    // com.factoryflow.<서비스>.. 에 있으면 그 서비스 이름, 아니면 null
    private static String serviceOf(JavaClass c) {
        String[] parts = c.getPackageName().split("\\.");
        if (parts.length < 3 || !parts[0].equals("com") || !parts[1].equals("factoryflow")) {
            return null;
        }
        return SERVICES.contains(parts[2]) ? parts[2] : null;
    }

    // Spring Data repository 면 엔티티 이름, 아니면 null (JpaRepository<CustomerOrder, Long> ->
    // CustomerOrder)
    private static String entityOf(JavaClass c) {
        if (serviceOf(c) == null || !c.isInterface()) {
            return null;
        }
        return c.getInterfaces().stream()
                .filter(JavaParameterizedType.class::isInstance)
                .map(JavaParameterizedType.class::cast)
                .filter(t -> t.toErasure().getName().startsWith("org.springframework.data."))
                .filter(t -> !t.getActualTypeArguments().isEmpty())
                .map(t -> t.getActualTypeArguments().get(0).toErasure().getSimpleName())
                .findFirst()
                .orElse(null);
    }

    // 반환 타입과 그 타입 인자에 들어 있는 클래스 (ApiResponse<List<X>> -> ApiResponse, List, X)
    private static Stream<JavaClass> typesIn(JavaType type) {
        Stream<JavaClass> self = Stream.of(type.toErasure());
        if (type instanceof JavaParameterizedType parameterized) {
            return Stream.concat(
                    self,
                    parameterized.getActualTypeArguments().stream()
                            .flatMap(FactoryFlowRules::typesIn));
        }
        return self;
    }

    private static boolean importsTestContainers(JavaClass c) {
        for (JavaClass type = c; type != null; type = type.getRawSuperclass().orElse(null)) {
            boolean imported =
                    type
                            .tryGetAnnotationOfType("org.springframework.context.annotation.Import")
                            .flatMap(a -> a.get("value"))
                            .stream()
                            .flatMap(FactoryFlowRules::strings)
                            .anyMatch(v -> v.contains(TEST_CONTAINERS_CONFIG));
            if (imported) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesTransactional(JavaClass c) {
        return serviceOf(c) != null
                && (c.isAnnotatedWith(SPRING_TRANSACTIONAL)
                        || c.getMethods().stream()
                                .anyMatch(m -> m.isAnnotatedWith(SPRING_TRANSACTIONAL)));
    }

    private static boolean isInClientPackage(JavaClass c) {
        return CLIENT_PACKAGE.matcher(c.getPackageName()).matches();
    }

    private static boolean isHandler(JavaMethod m) {
        return HANDLER_MAPPINGS.stream().anyMatch(m::isAnnotatedWith);
    }

    private static boolean isSimpleValueParam(JavaParameter p) {
        return SIMPLE_VALUE_PARAMS.stream().anyMatch(p::isAnnotatedWith);
    }

    private static boolean hasStaticFactory(JavaClass c) {
        return c.getMethods().stream()
                .anyMatch(
                        m ->
                                (m.getName().equals("from") || m.getName().equals("of"))
                                        && m.getModifiers().contains(JavaModifier.PUBLIC)
                                        && m.getModifiers().contains(JavaModifier.STATIC)
                                        && m.getRawReturnType().getName().equals(c.getName()));
    }

    private static List<String> mappedPaths(JavaClass c) {
        return c.tryGetAnnotationOfType(REQUEST_MAPPING)
                .map(
                        a ->
                                Stream.of("value", "path")
                                        .flatMap(key -> a.get(key).stream())
                                        .flatMap(FactoryFlowRules::strings)
                                        .toList())
                .orElse(List.of());
    }

    private static Stream<String> strings(Object value) {
        return value instanceof Object[] array
                ? Arrays.stream(array).map(String::valueOf)
                : Stream.of(String.valueOf(value));
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private FactoryFlowRules() {}
}
