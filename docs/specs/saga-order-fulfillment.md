# 주문 이행 SAGA

## 목적
고객 주문 하나가 order → production(MES) → inventory (→ purchase) 를 거쳐 생산·출고되기까지의 흐름을 정한다.
기준은 기획서(docs/planning/production-flow.md)다. 방식은 코레오그래피(서비스가 이벤트를 받고 다음 이벤트를 발행)이고,
별도 SAGA 테이블 없이 **production.status 가 SAGA 상태**다. 단위는 **생산 건 = 차량 1대**다 (주문에서 같은 차 3대면 생산 건 3건).

## 상태

### 생산 건 (production, 차량 1대)
| 상태 | 뜻 | 들어오는 때 |
|---|---|---|
| PLANNED | 부품 예약 요청함 | order.created 수신 |
| WAITING_MATERIAL | 부품 일부가 입고를 기다림 | inventory.reservation-pending |
| READY | 부품 예약 끝, 작업지시 발행 (첫 공정 대기) | inventory.reserved |
| IN_PROGRESS | 첫 공정(프레스)을 시작함. 이후 취소할 수 없다 | 프레스 시작 |
| COMPLETED | 검사 양품 → VIN 발행 | 검사 종료(양품) |
| CANCELLED | 프레스 시작 전에 취소됨 | order.cancel-requested 승인 |
| FAILED | 설비 고장으로 생산 실패 | equipment.status-changed(FAULT) |

### 작업지시 (MES)
`work_order` 1 : N `work_order_step` 1 : N `production_result` (기획서 "공정 데이터 구조")
- work_order: 생산 건 1건당 1건 (inventory.reserved 때 생성). 상태 RELEASED → IN_PROGRESS → COMPLETED / CANCELLED / FAILED. VIN 을 가진다
- work_order_step: 라우팅 순서대로 5개. PRESS → BODY → PAINT → ASSEMBLY → INSPECTION. 상태 PENDING → IN_PROGRESS → DONE
- production_result: 공정을 끝낼 때마다 한 줄. 양품(GOOD) 또는 불량(DEFECT, 사유), 작업자, 시각

### 설비 (MES)
- equipment: 공정마다 설비. 상태 RUNNING(가동) / STOPPED(정지) / FAULT(고장). 상태가 바뀔 때마다 이력을 남긴다 (OEE 데이터)

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
2. production: 품목의 수량만큼 생산 건(차량 1대씩, PLANNED)을 만들고, 생산 건마다 BOM 으로 필요한 부품을 계산해
   `production.material-requested` 를 보낸다. 생산 건마다 `production.status-changed`(PLANNED) 를 보내 order 가 차량을 알게 한다
3. inventory: 부품마다 예약 줄 생성
   - 같은 부품에 WAITING 줄이 없고 available 이 충분하면 RESERVED (available −, reserved +, stock_history RESERVE)
   - 그 외에는 WAITING (재고는 건드리지 않음). 앞에 기다리는 줄이 있으면 재고가 있어도 새 줄은 뒤에 선다 (먼저 온 순서 보장)
   - 새 WAITING 때문에 부족이 늘었으면 `inventory.shortage` (shortQty 계산은 아래 "부족 수량")
   - purchase: shortage 수신 시 같은 부품의 AUTO + REQUESTED 구매 건이 있으면 그 qty 에 더하고, 없으면 새로 만든다
   - 생산 건의 모든 줄이 RESERVED → `inventory.reserved`, 하나라도 WAITING → `inventory.reservation-pending`
4. production: reservation-pending → WAITING_MATERIAL + `production.status-changed`
5. 입고: purchase RECEIVED → `purchase.received` → inventory available + (stock_history RECEIVE),
   그 부품의 WAITING 줄을 id 순서(먼저 온 순서)로 RESERVED 처리. 어떤 생산 건의 모든 줄이 RESERVED 가 되면 `inventory.reserved`
6. production: **inventory.reserved 가 생산의 트리거**다. 생산 건을 READY 로 바꾸고, 작업지시 1건(RELEASED)과
   라우팅 순서대로 공정 5개(PENDING)를 만든다 + `production.status-changed`(READY)
7. 라인 작업자가 공정을 순서대로 진행한다 (작업자 API, 아래 "공정 진행")
   - 프레스 시작 → 생산 건 IN_PROGRESS, 작업지시 IN_PROGRESS + `production.status-changed`(IN_PROGRESS)
   - 공정 종료 때 실적(양품/불량) 입력. 양품이면 그 공정 DONE, 다음 공정 시작 가능. 불량이면 실적만 남기고 같은 공정을 다시 한다
8. 검사 종료(양품) → VIN 발행 → 작업지시·생산 건 COMPLETED → `production.completed`
9. inventory: completed 수신 → 그 생산 건 줄 CONSUMED (reserved −, stock_history CONSUME), 완성차 재고 증가(VIN),
   소모한 부품 LOT ↔ VIN 연결
10. order: status-changed / completed 수신 → 차량 상태 갱신 → 품목·주문 상태 요약 갱신 (같은 트랜잭션)
11. 출고: 완성차를 출고하면 `shipment.completed` → order 가 차량을 SHIPPED 로 (주체와 세부는 출고 하위 이슈에서 정한다)

### 2. 공정 진행 (작업자, MES)
- 공정은 라우팅 순서대로만 시작한다. 앞 공정이 DONE 이어야 다음 공정을 시작할 수 있다. 한 작업지시에서 동시에 하나만 IN_PROGRESS
- 공정 시작은 그 공정의 설비가 RUNNING 일 때만 된다 (정지·고장 중이면 409)
- 공정 종료는 실적과 함께 한다: 양품 → DONE, 불량(사유) → 실적만 남기고 PENDING 으로 돌아가 재작업
- 시작·종료 시각과 작업자를 실적에 남긴다. 공정별 양품·불량 수는 실적에서 센다

### 3. 설비 상태와 생산 실패 (보상)
1. simulator(가상 설비)가 설비 상태를 바꿀 때마다 `equipment.status-changed` 를 보낸다 (가동·정지·고장). simulator 는 production API 를 부르지 않는다
2. production: 설비 상태와 이력을 저장한다 (OEE 데이터)
3. 고장(FAULT)이면 그 설비 공정을 진행 중(IN_PROGRESS)인 차량마다 작업지시·생산 건 FAILED → `production.failed`
4. inventory: failed 수신 → 그 생산 건 예약 줄 RELEASED (RESERVED 줄은 available +, reserved −, stock_history RELEASE). 자동 재생산은 없다
5. order: failed 수신 → 차량 FAILED

### 4. 취소 (보상)
첫 공정(프레스) 시작이 되돌릴 수 없는 지점이다. 생산 순서가 확정되면 협력업체가 그 순서대로 부품을 보내기 때문이다 (기획서 "취소 기준").
취소 요청은 주문 전체 단위이고, 판단은 차량마다 production 이 한다.
1. order: 고객 취소 요청
   - 차량 중 COMPLETED, SHIPPED 가 있으면 바로 거절 (409)
   - 아니면 차량을 모두 CANCEL_REQUESTED 로 바꾸고 `order.cancel-requested`
   - order 가 가진 차량 상태는 늦을 수 있으므로, 취소 가능 여부의 최종 판단은 production 이 한다
2. production: 그 주문의 생산 건마다 판단
   - 프레스 시작 전 (PLANNED, WAITING_MATERIAL, READY) → 생산 건·작업지시 CANCELLED + `production.cancelled`
   - 프레스 시작 후 (IN_PROGRESS, COMPLETED) → 상태 유지 + `production.cancel-rejected`(reason = ALREADY_STARTED)
   - 그 주문의 생산 건이 아직 없음 → `production.cancel-rejected`(reason = NOT_READY)
3. inventory: cancelled 수신 → 그 생산 건의 RESERVED 줄 RELEASED (available +, reserved −, stock_history RELEASE),
   WAITING 줄은 RELEASED (재고 변화 없음). 이미 들어간 AUTO 구매는 취소하지 않는다 (재고로 남음)
4. order: 차량마다 cancelled → CANCELLED, cancel-rejected → 생산 건의 현재 상태로 되돌림.
   그 주문에 CANCEL_REQUESTED 차량이 남지 않으면, 취소된 차량이 하나라도 있을 때 `order.cancelled`

## 규칙 / 예외

### 상태 매핑 (production → order 의 차량)
order 는 차량(생산 건)마다 상태를 갖고, 품목 상태는 그 품목 차량들의 요약, 주문 상태는 품목들의 요약이다.

| 생산 건 | order 의 차량 |
|---|---|
| PLANNED | RECEIVED |
| WAITING_MATERIAL | WAITING_MATERIAL |
| READY, IN_PROGRESS | IN_PRODUCTION |
| COMPLETED | COMPLETED |
| CANCELLED | CANCELLED |
| FAILED | FAILED |

출고되면 SHIPPED, 취소 응답 대기는 CANCEL_REQUESTED 가 더 있다.

### 요약 (차량 → 품목, 품목 → 주문. 바뀐 트랜잭션에서 함께 계산)
CANCELLED 는 빼고 남은 것으로 계산한다. 위에서부터 먼저 맞는 것을 쓴다.
1. 남은 것이 없음 → CANCELLED
2. 하나라도 CANCEL_REQUESTED → CANCEL_REQUESTED
3. 남은 것이 모두 FAILED → FAILED (FAILED 는 아래 규칙에서 빼고 본다)
4. 모두 SHIPPED → SHIPPED
5. 모두 COMPLETED 또는 SHIPPED → COMPLETED
6. 하나라도 IN_PRODUCTION, COMPLETED, SHIPPED → IN_PRODUCTION
7. 하나라도 WAITING_MATERIAL → WAITING_MATERIAL
8. 그 외 → RECEIVED

### 순서 꼬임과 중복
- 같은 이벤트 중복 수신: ProcessedEventGuard 가 막는다
- 상태 전이는 허용된 이전 상태에서만 한다. 허용되지 않은 상태에서 온 이벤트는 로그만 남기고 무시한다
  (예: CANCELLED 생산 건에 온 inventory.reserved)
- 취소되거나 실패한 생산 건에 inventory.reserved 가 늦게 오면 production 은 `production.cancelled` 를 다시 보낸다
  (inventory 해제는 RESERVED/WAITING 줄에만 적용되므로 여러 번 받아도 안전)
- 취소가 생산 건 생성보다 먼저 도착하면 NOT_READY 로 거절한다. 고객은 다시 취소를 요청한다
- 같은 생산 건을 동시에 바꾸는 것(프레스 시작 vs 취소 수신, 설비 고장 vs 공정 종료)은 생산 건의 낙관적 락(@Version)으로 막는다.
  늦은 쪽 API 는 409, 리스너는 재시도로 최신 상태를 보고 다시 판단한다

### 부족 수량 (shortQty)
대기 중인 수량까지 포함해 "이번 요청으로 새로 늘어난 부족분"만 보낸다. 같은 부족을 두 번 구매하지 않기 위해서다.
- 이전 부족 = max(0, 그 부품의 WAITING qty 합 − available)
- 이후 부족 = max(0, 그 부품의 WAITING qty 합 + 이번 qty − available)
- shortQty = 이후 − 이전. 0 이면 shortage 를 보내지 않는다
- 예: available 3, 대기 2, 새 요청 2 → 이전 0, 이후 1 → shortQty 1
- WAITING qty 합은 줄을 다 읽지 않고 DB 에서 합계로 구한다

### 목록 조회
목록은 한 번에 다 읽지 않는다. id 커서(`id > lastId ORDER BY id LIMIT size`)로 나눠 준다 (작업지시 목록, 설비 이력 등).

### 멈춤 감시
- WAITING_MATERIAL 은 입고까지 며칠 걸릴 수 있으므로 자동 보상하지 않는다
- production 스케줄러가 WAITING_MATERIAL 로 설정 시간(기본 24시간) 넘게 머문 생산 건을 WARN 로그로 남긴다 (`SAGA_STUCK productionId=…`)
- order 스케줄러가 CANCEL_REQUESTED 로 10분 넘게 머문 차량을 WARN 로그로 남긴다
- 로그는 CloudWatch 지표 필터로 알람을 걸 수 있다 (이후 작업)

### 추적
order.created 의 traceId 를 이 주문에서 이어지는 이벤트에 그대로 쓴다. 로그에서 traceId 하나로 SAGA 전체를 본다.
작업자 API 와 설비 이벤트로 시작하는 이벤트는 그 요청·이벤트의 traceId 를 쓴다.

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 | 수신 | payload |
|---|---|---|---|
| order.created | order | production | orderId, products[{productId, qty}] |
| order.cancel-requested | order | production | orderId |
| order.cancelled | order | (없음, 이후 알림·출고) | orderId |
| production.material-requested | production | inventory | productionId, parts[{partId, qty}] |
| production.status-changed | production | order | orderId, productionId, productId, status |
| production.cancelled | production | inventory, order | orderId, productionId |
| production.cancel-rejected | production | order | orderId, productionId, reason (ALREADY_STARTED, NOT_READY) |
| production.failed | production | inventory, order | orderId, productionId, equipmentCode, reason (EQUIPMENT_FAULT) |
| production.completed | production | inventory, order | orderId, productionId, productId, vin |
| inventory.reserved | inventory | production | productionId |
| inventory.reservation-pending | inventory | production | productionId, shortages[{partId, shortQty}] |
| inventory.shortage | inventory | purchase | partId, partCode, partName, shortQty |
| purchase.received | purchase | inventory | partId, qty |
| equipment.status-changed | simulator | production | equipmentCode, process, status (RUNNING, STOPPED, FAULT) |
| shipment.completed | (출고 이슈에서 정함) | order | orderId, productionId, vin |

## 완료 조건 (테스트로 확인할 것)
하위 이슈마다 나눠 확인한다.
- [ ] 없는 제품 또는 BOM 없는 제품 주문은 404, 주문과 이벤트가 생기지 않는다
- [ ] 같은 차 3대 주문은 생산 건 3건, 예약 요청 3건이 된다
- [ ] inventory.reserved 를 받으면 작업지시 1건과 공정 5개가 라우팅 순서대로 생긴다
- [ ] 공정은 순서대로만, 설비가 RUNNING 일 때만 시작된다
- [ ] 공정 불량은 실적에 남고 같은 공정을 다시 한다. 이벤트는 없다
- [ ] 검사 양품 → VIN 발행, production.completed, 부품 CONSUMED, 완성차 재고 증가, 주문 COMPLETED
- [ ] 재고 부족: WAITING + AUTO 구매, 입고 후 먼저 대기한 순서대로 RESERVED, 작업지시 생성
- [ ] 설비 고장: 그 공정 진행 중 차량 FAILED, 예약 해제, 차량 FAILED
- [ ] 프레스 시작 전 취소: 예약 해제, 차량 CANCELLED, order.cancelled
- [ ] 프레스 시작 후 취소: cancel-rejected, 차량 상태 복귀
- [ ] 취소 후 늦게 온 inventory.reserved 는 해제로 정리된다
- [ ] 같은 이벤트를 두 번 받아도 재고와 상태가 한 번만 바뀐다
- [ ] part.reserved 는 항상 그 부품의 RESERVED 예약 qty 합과 같다
- [ ] shortQty 는 대기 수량을 포함한 증가분만 계산된다 (예: available 3, 대기 2, 새 요청 2 → 1)
- [ ] 같은 부품 부족이 여러 번 오면 REQUESTED 상태의 AUTO 구매 1건에 수량이 합쳐진다
