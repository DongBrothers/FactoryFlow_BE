# FactoryFlow
자동차 공장 모델 ERP+MES. Java 17, Spring Boot 4.0.8, Gradle(Groovy) 멀티모듈.

## 명령
- 서비스 검사: ./gradlew :services:<svc>:check
- 전체: ./gradlew check
- 포맷 정리: ./gradlew spotlessApply
- 로컬 인프라: docker compose up -d

## ArchUnit 이 검사하는 규칙
패키지 위치, 이름 접미사, 주입 방식, 로그, DTO(record, 정적 팩토리), client, 이벤트, Swagger 어노테이션과 summary/description/example 값, 테스트 건너뛰기(@Disabled*, @Enabled*, Assumptions, disabledWithoutDocker), mock 대상(필드, Mockito.mock/spy), 데이터 접근(@Query·EntityManager 쿼리·JDBC 금지), 컨트롤러의 service 결과 처리(변수에 담은 뒤 응답에 넣기)는 common/common-test 의 FactoryFlowRules 가 검사한다 (각 서비스 ArchitectureTest, TestArchitectureTest). 규칙 목록과 이유는 그 파일을 보고, check 실패 메시지를 따른다. 아래에는 ArchUnit 으로 검사할 수 없는 규칙만 둔다.

## 절대 규칙
- 서비스 간 통신: 비동기는 이벤트(common-event), 동기는 client/
- 요청 값 검증은 Bean Validation. @RequestBody DTO 는 필드 제약, @RequestParam/@PathVariable 은 파라미터에 제약 (Spring 내장 검증). 기본 제약으로 안 되는 형식 규칙은 커스텀 제약. 실패는 공통 핸들러가 400. 서비스는 비즈니스 규칙(없음 404, 충돌 409)만 확인
- 테스트 삭제, 의미 없는 assert 금지. 테스트가 틀렸다고 판단되면 이유를 말하고 멈춘다
- 테스트에서 DB, MQ, Redis 는 Testcontainers

## 패키지 구조 (서비스별)
- 엔티티는 domain/ 에 둔다
- 요청 전용 커스텀 제약은 dto/request 에 둔다
- 자동 쿼리 메서드로 안 되는 쿼리는 QueryDSL: repository/{엔티티}RepositoryCustom(인터페이스) + {엔티티}RepositoryImpl, {엔티티}Repository 가 Custom 을 상속한다. JPAQueryFactory Bean 은 config/QuerydslConfig. 쓰는 서비스는 build.gradle 에 querydsl-jpa, querydsl-apt(jpa) 를 추가한다

## 이름 규칙
- 서비스 간 내부 API(/internal/**)는 gateway 에 노출하지 않는다
- Service: 도메인(엔티티)별 {도메인}Service (예: OrderService, ProductService)
- Repository: {엔티티}Repository
- client: {상대 도메인}Client 의 구현은 config/client/{상대 서비스}/{상대 도메인}ClientConfig 가 HttpServiceProxyFactory 로 만든다. 호출 실패와 시간 초과는 client 의 default 메서드에서 {상대 서비스}_UNAVAILABLE(503) 로 바꾼다
- DTO: {동작}{도메인}Request/Response (예: CreateOrderRequest). 내부 API 응답은 Internal{도메인}Response
- 응답 DTO 정적 팩토리: 도메인 객체 하나를 바꿀 때 from(원본), 값 여러 개로 만들 때 of(...)
- 이벤트 payload 이름은 과거형 (예: OrderCreated)

## API 문서 (Swagger)
- @ApiResponse 는 응답 코드마다 (성공, 400, 404, 409, 503 등 실제로 나는 것 전부)
- 공통 응답 ApiResponse 와 이름이 같으므로 Swagger 쪽은 io.swagger.v3.oas.annotations.responses.ApiResponse 로 전체 이름을 쓴다

## 사람만 수정 (수정 필요 시 제안만)
CLAUDE.md(루트), .claude/, .github/, common/common-event/, common/common-test/, docs/specs/events.md, 루트 build.gradle, settings.gradle

## 작업 순서
1. docs/specs/ 의 해당 명세 확인 (없으면 _template.md로 초안 작성 후 확인 요청)
2. 테스트 먼저 → 구현
3. 이벤트 추가는 /add-event 사용
4. PR 생성 시 "## 왜" 섹션은 비워둔다 (작성자가 직접 씀)
