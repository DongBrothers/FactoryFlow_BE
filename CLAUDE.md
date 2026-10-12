# FactoryFlow
자동차 공장 모델 ERP+MES. Java 17, Spring Boot 4.0.8, Gradle(Groovy) 멀티모듈.

## 명령
- 서비스 검사: ./gradlew :services:<svc>:check
- 전체: ./gradlew check
- 포맷 정리: ./gradlew spotlessApply
- 로컬 인프라: docker compose up -d

## ArchUnit 이 검사하는 규칙
패키지 위치, 이름 접미사, 주입 방식, 로그, DTO(record, 정적 팩토리), client, 이벤트, Swagger 어노테이션과 summary/description/example 값, 테스트 건너뛰기(@Disabled*, @Enabled*, Assumptions, disabledWithoutDocker), mock 대상(필드, Mockito.mock/spy), 데이터 접근(@Query·EntityManager 쿼리·JDBC 금지), 컨트롤러의 service 결과 처리(변수에 담은 뒤 응답에 넣기), 엔티티·커스텀 제약 위치, QueryDSL 구조(RepositoryCustom + RepositoryImpl), Repository·Service 이름, ClientConfig(HttpServiceProxyFactory), 내부 API 응답 이름(Internal{도메인}Response), 응답 DTO from/of 인자 수, service 예외(CustomException), 통합 테스트의 Testcontainers, 트랜잭션(Spring @Transactional 만, service 계층에만, 같은 클래스 안 호출 금지, 트랜잭션 안 client 호출 금지) 은 common/common-test 의 FactoryFlowRules 가 검사한다 (각 서비스 ArchitectureTest, TestArchitectureTest). 규칙 목록과 이유는 그 파일을 보고, check 실패 메시지를 따른다. 아래에는 ArchUnit 으로 검사할 수 없는 규칙만 둔다.

## 절대 규칙
- 서비스 간 통신: 비동기는 이벤트(common-event), 동기는 client/
- 트랜잭션: 다른 서비스 호출(client)과 저장이 한 흐름에 있으면, 호출은 {도메인}Service 에서 트랜잭션 밖에 끝내고 저장은 별도 Bean({도메인}Writer) 의 @Transactional 메서드로 뺀다 (예: OrderService → OrderWriter)
- 요청 값 검증은 Bean Validation. @RequestBody DTO 는 필드 제약, @RequestParam/@PathVariable 은 파라미터에 제약 (Spring 내장 검증). 기본 제약으로 안 되는 형식 규칙은 커스텀 제약. 실패는 공통 핸들러가 400. 서비스는 비즈니스 규칙(없음 404, 충돌 409)만 확인
- 테스트 삭제, 의미 없는 assert 금지. 테스트가 틀렸다고 판단되면 이유를 말하고 멈춘다

## 패키지 구조 (서비스별)
- QueryDSL 을 쓰는 서비스는 build.gradle 에 querydsl-jpa, querydsl-apt(jpa) 를 추가한다. JPAQueryFactory 는 common-web 의 QuerydslAutoConfiguration 이 만든다

## 이름 규칙
- 서비스 간 내부 API(/internal/**)는 gateway 에 노출하지 않는다
- Service 는 도메인(엔티티)별로 둔다 (예: OrderService, ProductService)
- client: 호출 실패와 시간 초과는 client 의 default 메서드에서 {상대 서비스}_UNAVAILABLE(503) 로 바꾼다
- DTO: {동작}{도메인}Request/Response (예: CreateOrderRequest)
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
