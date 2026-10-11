# 주문 이행 SAGA

## 목적
고객 주문 하나가 order → production → inventory (→ purchase) 를 거쳐 완료되기까지의 흐름을 정한다.
방식은 코레오그래피(서비스가 이벤트를 받고 다음 이벤트를 발행)이고, 별도 SAGA 테이블 없이
**production.status 가 SAGA 상태**다. 단위는 생산 건(주문 속 제품 1종)이다.

## 흐름

### 0. 입구 검증 (동기, SAGA 시작 전)
"없는 것" 때문에 생기는 실패는 이벤트로 처리하지 않는다. 요청을 받는 곳에서 바로 확인하고 거절한다.

| 요청 | 확인 | 방법 | 실패 시 |
|---|---|---|---|
| 주문 생성 (order) | 제품이 있고 BOM 이 있다 | order → production client (스냅샷 product_code, product_name 도 함께 받음) | 제품 또는 BOM 없음 404. 주문 생성 안 함 |
| BOM 등록 (production) | 부품이 있다 | production → inventory client | 404 |
| 부품 삭제 (inventory) | 그 부품을 쓰는 BOM 이 없다 | inventory → production client | 409, 삭제 안 함 |
| 수동 구매 (purchase) | 부품이 있다 | purchase → inventory client (스냅샷 part_code, part_name 도 함께 받음) | 404 |

client 호출은 서비스 간 내부 API 로 하고 gateway 에 노출하지 않는다.

### 1. 성공 흐름
1. order: 주문 생성 (customer_order, order_item = RECEIVED) → `order.created`
2. production: 제품마다 생산 건 생성 (PLANNED), BOM × 수량으로 필요한 부품 계산 → `production.material-requested`
3. inventory: 부품마다 예약 줄 생성
   - 같은 부품에 WAITING 줄이 없고 available 이 충분하면 RESERVED (available −, reserved +, stock_history RESERVE)
   - 그 외에는 WAITING (재고는 건드리지 않음). 앞에 기다리는 줄이 있으면 재고가 있어도 새 줄은 뒤에 선다 (먼저 온 순서 보장)
   - 새 WAITING 때문에 부족이 늘었으면 `inventory.shortage` (shortQty 계산은 아래 "부족 수량")
   - purchase: shortage 수신 시 같은 부품의 AUTO + REQUESTED 구매 건이 있으면 그 qty 에 더하고, 없으면 새로 만든다
   - 생산 건의 모든 줄이 RESERVED → `inventory.reserved`, 하나라도 WAITING → `inventory.reservation-pending`
4. production: reserved → READY, reservation-pending → WAITING_MATERIAL. 상태가 바뀔 때마다 `production.status-changed`
5. 입고: purchase RECEIVED → `purchase.received` → inventory available + (stock_history RECEIVE),
   그 부품의 WAITING 줄을 id 순서(먼저 온 순서)로 RESERVED 처리. 어떤 생산 건의 모든 줄이 RESERVED 가 되면 `inventory.reserved`
6. simulator(생산 라인)가 production 내부 API 를 호출한다: READY 건 조회 → 시작 → 완료 (가끔 불량)
   production: READY → IN_PROGRESS (started_at) → COMPLETED (completed_at) → `production.completed`
7. inventory: completed 수신 → 그 생산 건 줄 CONSUMED (reserved −, stock_history CONSUME)
8. order: status-changed / completed 수신 → order_item 상태 갱신, customer_order.status 요약 갱신 (같은 트랜잭션)

### 2. 취소 (보상)
생산 시작(IN_PROGRESS)이 되돌릴 수 없는 지점이다. 취소는 주문 전체 단위다.
1. order: 고객 취소 요청
   - 품목 중 COMPLETED, CANCELLED 가 있으면 바로 거절 (409)
   - 아니면 모든 품목을 CANCEL_REQUESTED 로 바꾸고 `order.cancelled`
   - order 가 가진 품목 상태는 늦을 수 있으므로, 취소 가능 여부의 최종 판단은 production 이 한다
2. production: 생산 건마다 판단
   - PLANNED, WAITING_MATERIAL, READY → CANCELLED + `production.cancelled` + `production.status-changed`(CANCELLED)
   - IN_PROGRESS, COMPLETED → 상태 유지 + `production.cancel-rejected`(reason = ALREADY_STARTED)
   - 그 주문의 생산 건이 아직 없음 → `production.cancel-rejected`(reason = NOT_READY)
3. inventory: cancelled 수신 → 그 생산 건의 RESERVED 줄 RELEASED (available +, reserved −, stock_history RELEASE),
   WAITING 줄은 RELEASED (재고 변화 없음). 이미 들어간 AUTO 구매는 취소하지 않는다 (재고로 남음)
4. order: 품목마다
   - status-changed(CANCELLED) → CANCELLED
   - cancel-rejected → 생산 건의 현재 상태로 되돌림 (ALREADY_STARTED 면 IN_PRODUCTION, NOT_READY 면 RECEIVED)
   - 일부 품목만 취소될 수 있다 (예: A 는 생산 중이라 거절, B 는 취소)

### 3. 불량 재생산
1. production: 생산 중 불량 → 생산 건을 WAITING_MATERIAL 로 되돌리고 `production.failed`
2. inventory: failed 수신
   - 그 생산 건의 RESERVED 줄을 소모 처리 (reserved −, stock_history CONSUME): 불량에 쓴 부품
   - **같은 예약 줄을 재사용**해 다시 예약: available 이 충분하면 RESERVED, 아니면 WAITING + `inventory.shortage`
   - 결과에 따라 `inventory.reserved` 또는 `inventory.reservation-pending`
3. production: 이후는 성공 흐름 4번부터 같다
4. order: 품목은 IN_PRODUCTION 유지 (고객에게는 지연일 뿐). production 은 started_at 이 있는 생산 건(한 번 시작한 건)의
   재생산 중 상태 변화에는 status-changed 를 보내지 않고, COMPLETED 때 completed 만 보낸다

재사용하는 예약 줄은 처음 id 를 유지하므로 WAITING 대기 순서에서 앞선다 (재작업 우선).

## 규칙 / 예외

### 상태 매핑 (production → order_item)
| production.status | order_item.status |
|---|---|
| PLANNED | RECEIVED |
| WAITING_MATERIAL | WAITING_MATERIAL |
| READY, IN_PROGRESS | IN_PRODUCTION |
| COMPLETED | COMPLETED |
| CANCELLED | CANCELLED |

order_item 에는 CANCEL_REQUESTED (취소 응답 대기) 가 추가된다. FAILED 는 이 흐름에서 쓰지 않는다 (입구 검증 + 재생산).

### customer_order.status 요약 (품목이 바뀔 때 같은 트랜잭션에서 계산)
CANCELLED 품목은 제외하고 남은 품목으로 계산한다. 위에서부터 먼저 맞는 것을 쓴다.
1. 모든 품목이 CANCELLED → CANCELLED
2. 하나라도 CANCEL_REQUESTED → CANCEL_REQUESTED
3. 남은 품목이 모두 COMPLETED → COMPLETED
4. 하나라도 IN_PRODUCTION 또는 COMPLETED → IN_PRODUCTION
5. 하나라도 WAITING_MATERIAL → WAITING_MATERIAL
6. 그 외 → RECEIVED

### 순서 꼬임과 중복
- 같은 이벤트 중복 수신: ProcessedEventGuard 가 막는다
- 상태 전이는 허용된 이전 상태에서만 한다. 허용되지 않은 상태에서 온 이벤트는 로그만 남기고 무시한다
  (예: CANCELLED 생산 건에 온 inventory.reserved)
- 취소된 생산 건에 inventory.reserved 가 늦게 오면 production 은 `production.cancelled` 를 다시 보낸다
  (inventory 해제는 RESERVED/WAITING 줄에만 적용되므로 여러 번 받아도 안전)
- 취소가 생산 건 생성보다 먼저 도착하면 NOT_READY 로 거절한다. 고객은 다시 취소를 요청한다

### 부족 수량 (shortQty)
대기 중인 수량까지 포함해 "이번 요청으로 새로 늘어난 부족분"만 보낸다. 같은 부족을 두 번 구매하지 않기 위해서다.
- 이전 부족 = max(0, 그 부품의 WAITING qty 합 − available)
- 이후 부족 = max(0, 그 부품의 WAITING qty 합 + 이번 qty − available)
- shortQty = 이후 − 이전. 0 이면 shortage 를 보내지 않는다
- 예: available 3, 대기 2, 새 요청 2 → 이전 0, 이후 1 → shortQty 1

### 생산 라인 (simulator)
- simulator 는 production 내부 API 만 호출한다 (READY 목록 조회, 시작, 완료, 불량). 이벤트를 발행하거나 받지 않는다
- 불량 호출은 이 문서의 "3. 불량 재생산" 으로 이어진다

### 멈춤 감시
- WAITING_MATERIAL 은 입고까지 며칠 걸릴 수 있으므로 자동 보상하지 않는다
- production 스케줄러가 WAITING_MATERIAL 로 설정 시간(기본 24시간) 넘게 머문 생산 건을 WARN 로그로 남긴다 (`SAGA_STUCK productionId=…`)
- order 스케줄러가 CANCEL_REQUESTED 로 10분 넘게 머문 품목을 WARN 로그로 남긴다
- 로그는 CloudWatch 지표 필터로 알람을 걸 수 있다 (이후 작업)

### 추적
order.created 의 traceId 를 이 주문에서 이어지는 모든 이벤트에 그대로 쓴다. 로그에서 traceId 하나로 SAGA 전체를 본다.

## 관련 이벤트 (events.md 참조)
기존: order.created, order.cancelled, production.material-requested, production.cancelled,
production.status-changed, production.completed, inventory.reserved, inventory.reservation-pending,
inventory.shortage, purchase.received

추가:
| 이벤트 | 발행 | 수신 | payload |
|---|---|---|---|
| production.failed | production | inventory | orderId, productionId |
| production.cancel-rejected | production | order | orderId, productionId, reason (ALREADY_STARTED, NOT_READY) |

## 완료 조건 (테스트로 확인할 것)
- [ ] 없는 제품 또는 BOM 없는 제품 주문은 404, 주문과 이벤트가 생기지 않는다
- [ ] inventory 에 없는 부품으로 BOM 을 등록하면 404, BOM 에서 쓰는 부품 삭제는 409
- [ ] 재고가 충분한 주문: RESERVED → READY → IN_PROGRESS → COMPLETED, 부품 CONSUMED, 주문 COMPLETED
- [ ] 재고 부족: WAITING + AUTO 구매, 입고 후 먼저 대기한 순서대로 RESERVED, 생산 건 READY
- [ ] 제품 2종 주문에서 A 는 바로 생산, B 는 대기 → 주문 IN_PRODUCTION, 품목 상태가 각각 다름
- [ ] 생산 시작 전 취소: 예약 해제, available 복구, stock_history RELEASE, 품목과 주문 CANCELLED
- [ ] 생산 중 취소: cancel-rejected, 품목 IN_PRODUCTION 으로 복귀
- [ ] 불량: 부품 CONSUME 기록 후 같은 예약 줄로 재예약, 주문 품목은 IN_PRODUCTION 유지
- [ ] 취소 후 늦게 온 inventory.reserved 는 해제로 정리된다
- [ ] 같은 이벤트를 두 번 받아도 재고와 상태가 한 번만 바뀐다
- [ ] part.reserved 는 항상 그 부품의 RESERVED 예약 qty 합과 같다
- [ ] 같은 부품에 대기 줄이 있으면 재고가 있어도 새 요청은 WAITING 으로 뒤에 선다
- [ ] shortQty 는 대기 수량을 포함한 증가분만 계산된다 (예: available 3, 대기 2, 새 요청 2 → 1)
- [ ] 같은 부품 부족이 여러 번 오면 REQUESTED 상태의 AUTO 구매 1건에 수량이 합쳐진다
- [ ] simulator 의 시작, 완료, 불량 호출로 생산 건 상태가 바뀐다
