# 주문 생성

## 목적
고객이 제품 여러 종을 한 번에 주문한다. 주문할 수 있는 제품인지 확인하고, 주문과 품목을 저장한 뒤
`order.created` 를 발행해 SAGA(docs/specs/saga-order-fulfillment.md)를 시작한다.

## 흐름
1. 고객이 `POST /orders` 를 보낸다. 고객 번호는 gateway 가 로그인 확인 후 헤더 `X-User-Id` 로 넘긴다
   ```json
   { "items": [ { "productId": 1, "qty": 2 }, { "productId": 2, "qty": 1 } ] }
   ```
2. order 가 요청 형식을 Bean Validation(`@Valid`)으로 확인한다 (아래 규칙)
3. order 가 production 내부 API 로 제품을 한 번에 조회한다: `GET /internal/products?ids=1,2`
   ```json
   [ { "id": 1, "code": "CAR-A", "name": "소형차 A", "orderable": true } ]
   ```
   - 없는 id 는 응답에서 빠진다. `orderable` 은 BOM 이 하나 이상 있으면 true
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
| `X-User-Id` 헤더 없음 | 400 | 없음 |
| 요청 본문이 JSON 으로 읽히지 않음 (깨진 JSON, 숫자 자리에 문자 등) | 400 | 없음 |
| items 가 비어 있음 (`@NotEmpty`) | 400 | 없음 |
| productId 가 없음 (`@NotNull`) | 400 | 없음 |
| qty 가 1 미만 (`@Positive`) | 400 | 없음 |
| 같은 productId 가 두 번 나옴 (커스텀 제약 `@UniqueProductIds`) | 400 | 없음 |
| 없는 제품이 하나라도 있음 | 404 (PRODUCT_NOT_FOUND) | 없음 |
| BOM 없는 제품이 하나라도 있음 | 404 (BOM_NOT_FOUND) | 없음 |
| production 호출 실패 또는 시간 초과 (3초) | 503 (PRODUCTION_UNAVAILABLE) | 없음 |

- 주문은 전부 성공하거나 전부 실패한다. 일부 제품만 주문되지 않는다
- 요청 형식 검증은 모두 DTO 의 Bean Validation 으로 한다 (`@Valid`). 서비스에서 형식 검사를 다시 하지 않는다
- order_no 는 UUID, customer_order 에 UNIQUE (Flyway V3). 이벤트와 서비스 간 참조는 내부 id(orderId) 를 쓴다
- production 내부 API 는 gateway 에 노출하지 않는다 (서비스끼리만 호출)
- production 내부 API 의 `ids` 가 없거나 숫자가 아니면 400 이다
- 형식 오류(400)는 모두 common-web 공통 핸들러가 `VALIDATION_ERROR` 코드로 응답한다 (필수 헤더·파라미터 누락, 파라미터 형식 오류, 깨진 JSON, Bean Validation 실패)
- production 주소는 설정 `clients.production.url` 로 받는다 (환경별로 다름)

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| order.created | order → production | orderId, products[{productId, qty}] |

## 완료 조건 (테스트로 확인할 것)
- [ ] 정상 주문: 201 + orderNo(UUID), customer_order 1건(RECEIVED, order_no 저장), order_item 제품 수만큼(RECEIVED, 스냅샷 저장), outbox 에 order.created 1건
- [ ] order.created payload 에 orderId 와 products[{productId, qty}] 가 들어간다
- [ ] 헤더 없음 400, items 비어 있음 400, qty 0 이하 400, productId 중복 400
- [ ] 없는 제품 404, BOM 없는 제품 404. 둘 다 주문과 outbox 가 생기지 않는다
- [ ] production 장애 또는 3초 초과 시 503, 주문과 outbox 가 생기지 않는다
- [ ] order_no 는 UNIQUE 다 (같은 값 두 번 저장 불가)
- [ ] production `GET /internal/products?ids=` 는 있는 제품만 돌려주고, BOM 유무로 orderable 을 정한다
- [ ] 깨진 JSON 본문, `ids` 누락, `ids` 숫자 아님은 모두 400 VALIDATION_ERROR (공통 핸들러 테스트)
