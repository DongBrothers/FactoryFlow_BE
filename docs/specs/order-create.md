# 주문 생성

## 목적
고객이 제품 여러 종을 한 번에 주문한다. 주문할 수 있는 제품인지 확인하고, 주문과 품목을 저장한 뒤
`order.created` 를 발행해 SAGA(docs/specs/saga-order-fulfillment.md)를 시작한다.

## 흐름
1. `POST /orders` 를 보낸다. 주문은 로그인한 직원이 넣고, 어떤 고객의 주문인지는 요청 본문의 `customerId` 로 받는다 (업무 입력값). 헤더 `X-User-Id` 는 gateway 가 JWT 로 채우는 직원 식별자라 고객 번호로 쓰지 않는다. 클라이언트가 보낸 헤더는 신원으로 믿지 않는다
   ```json
   { "customerId": 7, "items": [ { "productId": 1, "qty": 2 }, { "productId": 2, "qty": 1 } ] }
   ```
2. order 가 요청 형식을 Bean Validation(`@Valid`)으로 확인하고, customerId 가 등록된 고객인지 확인한다 (docs/specs/customer.md, #125)
3. order 가 production 내부 API 로 제품을 한 번에 조회한다: `GET /internal/products?ids=1,2`
   ```json
   [ { "id": 1, "code": "CAR-A", "name": "소형차 A", "orderable": true } ]
   ```
   - 없는 id 와 삭제된 제품(is_deleted)은 응답에서 빠진다. `orderable` 은 삭제되지 않은 BOM 이 하나 이상 있으면 true
4. 한 트랜잭션에서
   - customer_order 저장 (order_no = 새 UUID, customer_id, status = RECEIVED)
   - order_item 저장 (제품마다 1줄, status = RECEIVED, product_code·product_name 은 3번 응답의 스냅샷)
   - `order.created` 를 outbox 에 저장 (EventPublisher)
5. `201 Created` 와 주문 번호(order_no)를 돌려준다. 내부 id 는 밖으로 내보내지 않는다
   ```json
   { "orderNo": "550e8400-e29b-41d4-a716-446655440000" }
   ```

## 규칙 / 예외
| 상황 | 응답 | 저장 / 이벤트 |
|---|---|---|
| `customerId` 없음 또는 1 미만 | 400 | 없음 |
| 요청 본문이 JSON 으로 읽히지 않음 (깨진 JSON, 숫자 자리에 문자 등) | 400 | 없음 |
| items 가 비어 있음 (`@NotEmpty`) | 400 | 없음 |
| productId 가 없음 (`@NotNull`) | 400 | 없음 |
| qty 가 1 미만 (`@Positive`) | 400 | 없음 |
| 같은 productId 가 두 번 나옴 (커스텀 제약 `@UniqueProductIds`) | 400 | 없음 |
| 등록되지 않은 고객 (docs/specs/customer.md, production 을 부르기 전에 확인) | 404 (CUSTOMER_NOT_FOUND) | 없음 |
| 없는 제품이 하나라도 있음 | 404 (PRODUCT_NOT_FOUND) | 없음 |
| BOM 없는 제품이 하나라도 있음 | 404 (BOM_NOT_FOUND) | 없음 |
| production 호출 실패, 시간 초과 (3초), 응답 본문이나 data 가 없는 비정상 응답 | 503 (PRODUCTION_UNAVAILABLE) | 없음 |

- 주문은 전부 성공하거나 전부 실패한다. 일부 제품만 주문되지 않는다
- 요청 형식 검증은 모두 DTO 의 Bean Validation 으로 한다 (`@Valid`). 서비스에서 형식 검사를 다시 하지 않는다
- order_no 는 UUID, customer_order 에 UNIQUE (Flyway V3). 이벤트와 서비스 간 참조는 내부 id(orderId) 를 쓴다
- production 내부 API 는 gateway 에 노출하지 않는다 (서비스끼리만 호출)
- production 내부 API 의 `ids` 가 없거나 숫자가 아니면 400 이다
- 형식 오류(400)는 모두 common-web 공통 핸들러가 `VALIDATION_ERROR` 코드로 응답한다 (필수 헤더·파라미터 누락, 파라미터 형식 오류, 깨진 JSON, Bean Validation 실패)
- 형식 오류 로그에는 클라이언트가 보낸 값을 남기지 않는다. 파라미터 이름과 타입, 본문 읽기 실패의 원인 예외 종류, Bean Validation 실패의 필드 이름과 제약 이름만 남긴다 (토큰·개인정보가 값으로 들어올 수 있음)
- production 주소는 설정 `clients.production.url` 로 받는다 (환경별로 다름)
- 두 API(`POST /orders`, `GET /internal/products`)는 springdoc OpenAPI 로 문서화한다. order, production 은 `/v3/api-docs` 와 Swagger UI 를 연다. 응답 코드는 API 마다 `@ApiResponse` 로 적고, 공통 예외 핸들러의 응답을 모든 API 에 자동으로 붙이지 않는다 (`springdoc.override-with-generic-response: false`)

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| order.created | order → production | orderId, products[{productId, qty}] |

## 완료 조건 (테스트로 확인할 것)
- [ ] 정상 주문: 201 + orderNo(UUID), customer_order 1건(RECEIVED, order_no 저장), order_item 제품 수만큼(RECEIVED, 스냅샷 저장), outbox 에 order.created 1건
- [ ] order.created payload 에 orderId 와 products[{productId, qty}] 가 들어간다
- [ ] 헤더 없음 400, items 비어 있음 400, qty 0 이하 400, productId 중복 400
- [ ] 등록되지 않은 고객 404, production 을 부르지 않는다
- [ ] 없는 제품 404, BOM 없는 제품 404. 둘 다 주문과 outbox 가 생기지 않는다
- [ ] production 장애, 3초 초과, 본문이나 data 가 없는 응답이면 503, 주문과 outbox 가 생기지 않는다. data 가 빈 정상 응답은 빈 목록(→ 없는 제품 404)
- [ ] order_no 는 UNIQUE 다 (같은 값 두 번 저장 불가)
- [ ] production `GET /internal/products?ids=` 는 있는 제품만 돌려주고, BOM 유무로 orderable 을 정한다. 삭제된 제품은 빠지고, 삭제된 BOM 만 있는 제품은 orderable false
- [ ] 깨진 JSON 본문, `ids` 누락, `ids` 숫자 아님은 모두 400 VALIDATION_ERROR (공통 핸들러 테스트)
- [ ] 파라미터 형식 오류, 읽을 수 없는 본문, Bean Validation 실패 때 보낸 값이 로그에 남지 않는다 (공통 핸들러 테스트)
- [ ] `/v3/api-docs` 에 두 API 의 summary, 실제로 나는 응답 코드(`POST /orders`: 201, 400, 404, 503 / `GET /internal/products`: 200, 400), 요청·응답 필드 설명이 나온다. `POST /orders` 에 500 은 나오지 않는다
