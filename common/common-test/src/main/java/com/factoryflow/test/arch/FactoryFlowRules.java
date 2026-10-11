package com.factoryflow.test.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
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
    private static final String AUTOWIRED =
            "org.springframework.beans.factory.annotation.Autowired";
    private static final String HTTP_EXCHANGE =
            "org.springframework.web.service.annotation.HttpExchange";
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
    private static final DescribedPredicate<JavaAnnotation<?>> DISABLING =
            describe(
                    "@Disabled or @Disabled* (org.junit.jupiter.api.condition)",
                    a ->
                            a.getRawType().getName().equals("org.junit.jupiter.api.Disabled")
                                    || a.getRawType()
                                            .getName()
                                            .startsWith(
                                                    "org.junit.jupiter.api.condition.Disabled"));
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

    // ---- 주입, 로그 ----

    public static final ArchRule NO_METHOD_INJECTION =
            noMethods()
                    .should()
                    .beAnnotatedWith(AUTOWIRED)
                    .because("생성자 주입만 쓴다 (setter, 메서드 주입 금지)");

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
                    .and(suffixInPackage("Client", "..client.*"))
                    .and(suffixInPackage("ClientConfig", "..config.client.*"))
                    .because("CLAUDE.md 패키지 구조를 따른다");

    public static final ArchRule CONFIG_PACKAGE =
            classes()
                    .that()
                    .areAnnotatedWith(CONFIGURATION)
                    .or()
                    .haveSimpleNameEndingWith("Config")
                    .should()
                    .resideInAPackage("..config..")
                    .because("설정은 config/ 에 둔다. 다른 서비스 호출 설정은 config/client/{상대 서비스}/");

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

    public static ArchRule errorCodeNamed(String me) {
        return classes()
                .that()
                .implement(ERROR_CODE)
                .should()
                .haveSimpleName(capitalize(me) + "ErrorCode")
                .andShould()
                .resideInAPackage("..exception")
                .because("에러 코드는 exception/{서비스}ErrorCode");
    }

    public static final ArchRule EVENT_LISTENER_NAMING =
            classes()
                    .that()
                    .resideInAPackage("..event.listener..")
                    .and()
                    .areTopLevelClasses()
                    .and()
                    .doNotHaveSimpleName("package-info")
                    .should()
                    .haveNameMatching(
                            SERVICES.stream()
                                    .map(FactoryFlowRules::capitalize)
                                    .collect(Collectors.joining("|", ".*\\.(", ")EventListener")))
                    .because("수신은 event/listener 의 {발행 서비스}EventListener");

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

    public static final ArchRule NO_RECORD_INSIDE_RECORD =
            classes()
                    .that(RECORDS)
                    .should(
                            check(
                                    "not be declared inside another record",
                                    c ->
                                            c.getEnclosingClass()
                                                    .filter(FactoryFlowRules::isRecord)
                                                    .isEmpty(),
                                    JavaClass::getName))
                    .because("record 안에 record 를 두지 않는다. 별도 파일로 뺀다");

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
                    .because("문서 없는 API 는 머지하지 않는다");

    public static final ArchRule DTO_FIELDS_DOCUMENTED =
            fields().that()
                    .areDeclaredInClassesThat(API_DTOS)
                    .and()
                    .areNotStatic()
                    .should()
                    .beAnnotatedWith(SCHEMA)
                    .because("요청/응답 DTO 필드는 @Schema(description, example) 로 문서화한다");

    // ---- 테스트 코드 (ImportOption.OnlyIncludeTests 로 등록) ----

    public static final ArchRule NO_DISABLED_TESTS =
            CompositeArchRule.of(noClasses().should().beAnnotatedWith(DISABLING))
                    .and(noMethods().should().beAnnotatedWith(DISABLING))
                    .because("Disabled 계열 어노테이션(@Disabled, @DisabledIf 등)으로 테스트를 끄지 않는다");

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
