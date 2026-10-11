# FactoryFlow
자동차 공장 모델 ERP+MES. Java 17, Spring Boot 4.0.8, Gradle(Groovy) 멀티모듈.

## 명령
- 서비스 검사: ./gradlew :services:<svc>:check
- 전체: ./gradlew check
- 포맷 정리: ./gradlew spotlessApply
- 로컬 인프라: docker compose up -d

## 절대 규칙
- 서비스 간 코드 직접 참조 금지. 비동기는 이벤트(common-event), 동기는 client/
- 외부에서 들어오는 호출(Controller, Listener)은 service 를 거친다. Repository 직접 호출 금지
- RabbitTemplate 직접 사용 금지. EventPublisher 사용
- 생성자 주입만. System.out 금지, 로그는 Slf4j
- 요청 값 검증은 Bean Validation. @RequestBody DTO 는 필드 제약 + @Valid, @RequestParam/@PathVariable 은 파라미터에 제약만 단다 (Spring 내장 검증, 컨트롤러에 @Validated 붙이지 않음). 기본 제약으로 안 되는 형식 규칙은 커스텀 제약. 실패는 공통 핸들러가 400. 서비스는 비즈니스 규칙(없음 404, 충돌 409)만 확인
- 테스트 삭제, @Disabled, 의미 없는 assert 금지. 테스트가 틀렸다고 판단되면 이유를 말하고 멈춘다
- 테스트에서 우리 코드(Repository, Service, EventPublisher)는 mock 금지. DB, MQ, Redis는 Testcontainers. mock은 client/(타 서비스 호출)와 Clock만

## 패키지 구조 (서비스별)
com.factoryflow.<svc>.{controller, domain, dto, repository, service, event/listener, event/publisher, client, exception}

## 이름 규칙
- Controller: {도메인}Controller. 서비스 간 내부 API(/internal/**)는 Internal{도메인}Controller (gateway 에 노출 안 함)
- Service: 도메인(엔티티)별 {도메인}Service (예: OrderService, ProductService)
- Repository: {엔티티}Repository
- client/: {상대 도메인}Client 는 HTTP Interface(@HttpExchange) 로 선언하고 구현은 {상대 도메인}ClientConfig 가 HttpServiceProxyFactory 로 만든다 (OpenFeign, RestTemplate 쓰지 않음). 응답은 {도메인}Info record. 호출 실패와 시간 초과는 client 의 default 메서드에서 {상대 서비스}_UNAVAILABLE(503) 로 바꾼다
- DTO: {동작}{도메인}Request/Response (예: CreateOrderRequest). 내부 API 응답은 Internal{도메인}Response
- 이벤트: 발행 payload 는 event/publisher 의 과거형 record (예: OrderCreated), 수신은 event/listener 의 {발행 서비스}EventListener
- 에러 코드: exception/{서비스}ErrorCode

## API 문서 (Swagger)
- 모든 API(외부, 내부)는 springdoc OpenAPI 로 문서화한다. 문서 없는 API 는 머지하지 않는다
- Controller 메서드: @Operation(summary, description) + 응답 코드마다 @ApiResponse (성공, 400, 404, 409, 503 등 실제로 나는 것 전부)
- 헤더, 쿼리, 경로 값: @Parameter(description, example)
- 요청/응답 DTO 필드: @Schema(description, example)
- 공통 응답 ApiResponse 와 이름이 같으므로 Swagger 쪽은 io.swagger.v3.oas.annotations.responses.ApiResponse 로 전체 이름을 쓴다

## 사람만 수정 (수정 필요 시 제안만)
CLAUDE.md(루트), .claude/, .github/, common/common-event/, common/common-test/, docs/specs/events.md, 루트 build.gradle, settings.gradle

## 작업 순서
1. docs/specs/ 의 해당 명세 확인 (없으면 _template.md로 초안 작성 후 확인 요청)
2. 테스트 먼저 → 구현
3. 이벤트 추가는 /add-event 사용
4. PR 생성 시 "## 왜" 섹션은 비워둔다 (작성자가 직접 씀)
