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
| IN_PROGRESS | 첫 공정(프레스)을 설비에 배정함(= 생산 순서 확정). 이후 취소할 수 없다 | 프레스 배정 (또는 작업자의 프레스 시작) |
| COMPLETED | 검사 양품 → VIN 발행 | 검사 종료(양품) |
| CANCELLED | 프레스 배정 전에 취소됨 | order.cancel-requested 승인 |

### 작업지시 (MES)
`work_order` 1 : N `work_order_step` 1 : N `production_result` (기획서 "공정 데이터 구조")
- work_order: 생산 건 1건당 1건 (inventory.reserved 때 생성). 상태 RELEASED → IN_PROGRESS → COMPLETED / CANCELLED. VIN 을 가진다
- work_order_step: 라우팅 순서대로 5개. PRESS → BODY → PAINT (로봇 공정) → ASSEMBLY → INSPECTION (사람 공정). 상태 PENDING → (DISPATCHED: 로봇 공정 배정) → IN_PROGRESS → DONE
- production_result: 공정을 끝낼 때마다 한 줄. 양품(GOOD) 또는 불량(DEFECT, 사유), 작업자, 시각

### 설비 (MES)
- equipment: 공정마다 여러 대 (예: PRESS-01, PRESS-02). 상태 RUNNING(가동) / STOPPED(정지) / FAULT(고장). 상태가 바뀔 때마다 이력을 남긴다 (OEE 데이터)

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
5. 입고: 구매 담당자가 발주(ORDERED) 후 입고 처리(RECEIVED) → `purchase.received` → inventory available + (stock_history RECEIVE),
   그 부품의 WAITING 줄을 id 순서(먼저 온 순서)로 RESERVED 처리 (앞 줄이 안 들어가면 거기서 멈춤). 어떤 생산 건의 모든 줄이 RESERVED 가 되면 `inventory.reserved` (상세: docs/specs/purchase-receiving.md)
6. production: **inventory.reserved 가 생산의 트리거**다. 생산 건을 READY 로 바꾸고, 작업지시 1건(RELEASED)과
   라우팅 순서대로 공정 5개(PENDING)를 만든다 + `production.status-changed`(READY)
7. 공정을 순서대로 진행한다 (아래 "공정 진행", 혼합 자동화)
   - 로봇 공정(프레스·차체·도장): production 의 배정 스케줄러가 비어 있는 설비에 배정 → `production.step-dispatched` → 설비(simulator)가 `equipment.step-started`, `equipment.step-completed`(양품/불량) 보고
   - 사람 공정(의장·검사): 라인 작업자가 작업자 API 로 시작·종료와 실적 입력
   - 프레스 배정(또는 작업자의 프레스 시작) → 생산 건 IN_PROGRESS, 작업지시 IN_PROGRESS + `production.status-changed`(IN_PROGRESS)
   - 양품이면 그 공정 DONE, 다음 공정으로. 불량이면 실적만 남기고 같은 공정을 다시 한다 (로봇 공정은 다시 배정)
8. 검사 종료(양품) → VIN 발행 → 작업지시·생산 건 COMPLETED → `production.completed`
9. inventory: completed 수신 → 그 생산 건 줄 CONSUMED (reserved −, stock_history CONSUME), 완성차 재고 증가(VIN),
   소모한 부품 LOT ↔ VIN 연결 (먼저 들어온 LOT 부터, 상세: docs/specs/part-lot.md)
10. order: status-changed / completed 수신 → 차량 상태 갱신 → 품목·주문 상태 요약 갱신 (같은 트랜잭션)
11. 출고: inventory 가 완성차 한 대(VIN)를 출고하면 `shipment.completed` → order 가 차량을 SHIPPED 로 (상세: docs/specs/shipment.md)

### 2. 공정 진행 (MES, 혼합 자동화)
- 공정은 라우팅 순서대로만 시작한다. 앞 공정이 DONE 이어야 다음 공정을 시작할 수 있다. 한 작업지시에서 동시에 하나만 DISPATCHED 또는 IN_PROGRESS
- **로봇 공정 배정** (PRESS, BODY, PAINT): production 스케줄러가 주기적으로, 앞 공정이 모두 DONE 이고 이 공정이 PENDING 인 작업지시를 그 공정의 RUNNING 이고 비어 있는(배정·진행 중인 공정이 없는) 설비에 작업지시 id 순서(먼저 발행된 차)로 배정한다. 공정 DISPATCHED + `production.step-dispatched`
  - 프레스도 작업지시가 발행되자마자 배정한다 (기다리지 않는다)
  - 설비 보고: `equipment.step-started` → IN_PROGRESS, `equipment.step-completed` → 실적(작업자 없음, 설비) + 양품 DONE / 불량 PENDING(다시 배정). 배정 회차(dispatchSeq)가 다른 보고는 무시 (docs/specs/simulator-restart.md)
  - 공정·설비가 맞지 않거나 상태가 맞지 않는 보고는 무시한다 (INFO 로그)
- **사람 공정** (ASSEMBLY, INSPECTION): 작업자가 시작할 때 그 공정의 설비를 지정한다. 작업자 API 는 로봇 공정에도 쓸 수 있다 (설비 연동 장애 때 수동 개입)
- 설비가 RUNNING 일 때만 배정·시작된다 (정지·고장 중이면 작업자 API 는 409)
- 공정 종료는 실적과 함께 한다: 양품 → DONE, 불량(사유) → 실적만 남기고 PENDING 으로 돌아가 재작업
- 시작·종료 시각과 작업자를 실적에 남긴다. 공정별 양품·불량 수는 실적에서 센다

### 3. 설비 상태 (고장·정지 시 재배정)
설비 고장은 공장 사정이라 고객 주문을 실패시키지 않는다. 생산 실패 상태는 없다 (2026-10-16 결정, 상세: docs/specs/equipment-status.md).
1. simulator(가상 설비)가 설비 상태를 바꿀 때마다 `equipment.status-changed` 를 보낸다 (가동·정지·고장). simulator 는 production API 를 부르지 않고 DB 도 없다. 측정값이라 Outbox 없이 바로 발행한다
2. production: 설비 상태와 이력을 저장한다 (OEE 데이터)
3. 고장(FAULT)·정지(STOPPED)면 **그 설비에** 배정되었거나(DISPATCHED) 진행 중(IN_PROGRESS)인 공정을 PENDING 으로 되돌리고 설비를 비운다
4. 되돌린 공정은 같은 공정의 다른 RUNNING 설비로 다시 배정된다 (사람 공정은 작업자가 다른 설비로 다시 시작). 다른 설비가 없으면 고쳐질 때까지 기다린다
5. 생산 건·작업지시·주문 상태와 부품 예약은 그대로다. 이벤트는 없다. 고객에게는 계속 "생산 중" (늦어질 뿐)

### 4. 취소 (보상)
첫 공정(프레스) 배정이 되돌릴 수 없는 지점이다. 프레스 배정이 생산 순서 확정이고, 순서가 확정되면 협력업체가 그 순서대로 부품을 보내기 때문이다 (기획서 "취소 기준").
취소 요청은 주문 전체 단위이고, 판단은 차량마다 production 이 한다.
1. order: 고객 취소 요청
   - 차량을 다 모르면(아직 생산 계획 중) 409 ORDER_NOT_READY, 차량 중 COMPLETED, SHIPPED 가 있으면 409 (상세: docs/specs/order-cancel.md)
   - 아니면 차량을 모두 CANCEL_REQUESTED 로 바꾸고 `order.cancel-requested`
   - order 가 가진 차량 상태는 늦을 수 있으므로, 취소 가능 여부의 최종 판단은 production 이 한다
2. production: 그 주문의 생산 건마다 판단
   - 프레스 배정 전 (PLANNED, WAITING_MATERIAL, READY) → 생산 건·작업지시 CANCELLED + `production.cancelled`
   - 프레스 배정 후 (IN_PROGRESS, COMPLETED) → 상태 유지 + `production.cancel-rejected`(reason = ALREADY_STARTED, status = 지금 상태)
   - 이미 취소된 생산 건 → `production.cancelled` 재발행
3. inventory: cancelled 수신 → 그 생산 건의 RESERVED 줄 RELEASED (available +, reserved −, stock_history RELEASE),
   WAITING 줄은 RELEASED (재고 변화 없음). 풀린 부품은 그 부품을 기다리던 줄에 먼저 온 순서로 넘긴다 (다 찬 생산 건은 inventory.reserved).
   이미 들어간 AUTO 구매는 취소하지 않는다 (재고로 남음)
4. order: 차량마다 cancelled → CANCELLED, cancel-rejected → payload 의 status(거절 때 생산 건 상태)로 되돌림.
   그 주문에 CANCEL_REQUESTED 차량이 남지 않으면, 이번 요청 회차에 취소된 차량이 하나라도 있을 때 `order.cancelled` (회차당 한 번)

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

출고되면 SHIPPED, 취소 응답 대기는 CANCEL_REQUESTED 가 더 있다.

### 요약 (차량 → 품목, 품목 → 주문. 바뀐 트랜잭션에서 함께 계산)
CANCELLED 는 빼고 남은 것으로 계산한다. 위에서부터 먼저 맞는 것을 쓴다.
1. 남은 것이 없음 → CANCELLED
2. 하나라도 CANCEL_REQUESTED → CANCEL_REQUESTED
3. 모두 SHIPPED → SHIPPED
4. 모두 COMPLETED 또는 SHIPPED → COMPLETED
5. 하나라도 IN_PRODUCTION, COMPLETED, SHIPPED → IN_PRODUCTION
6. 하나라도 WAITING_MATERIAL → WAITING_MATERIAL
7. 그 외 → RECEIVED

### 순서 꼬임과 중복
- 같은 이벤트 중복 수신: ProcessedEventGuard 가 막는다
- 상태 전이는 허용된 이전 상태에서만 한다. 허용되지 않은 상태에서 온 이벤트는 로그만 남기고 무시한다
  (예: CANCELLED 생산 건에 온 inventory.reserved)
- 취소된 생산 건에 inventory.reserved 가 늦게 오면 production 은 `production.cancelled` 를 다시 보낸다
  (inventory 해제는 RESERVED/WAITING 줄에만 적용되므로 여러 번 받아도 안전)
- 차량(생산 건)을 다 알기 전에 온 취소 요청은 order 가 409 ORDER_NOT_READY 로 거절한다. 고객은 잠시 후 다시 요청한다
- 같은 생산 건을 동시에 바꾸는 것(프레스 배정 vs 취소 수신)은 생산 건의 낙관적 락(@Version)으로, 설비 고장 vs 공정 시작·종료는 설비·작업지시의 낙관적 락으로 막는다.
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

traceId 는 **gateway 가 맡는다** (#67). 헤더 이름은 `X-Trace-Id`.

| 구간 | 규칙 |
|---|---|
| gateway (밖에서 들어온 요청) | **항상 새로 만든다** (UUID). 클라이언트가 보낸 `X-Trace-Id` 는 버린다 (`X-User-*` 와 같은 이유: 밖에서 온 값은 믿지 않는다). 뒤 서비스로 `X-Trace-Id` 를 넘기고, gateway 의 **모든 응답**(gateway 가 만든 401·403·404·5xx 포함)에 `X-Trace-Id` 를 붙인다 |
| 서비스 입구 | `X-Trace-Id` 가 있으면 그 값을, 없으면 새로 만들어 쓴다 (common-web `TraceIdFilter`, 원래 동작) |
| 이벤트 | 발행 때 traceId 를 담고, 받는 쪽은 처리하는 동안 그 값을 쓴다 (`EventPublisher`, `ProcessedEventGuard`, 원래 동작) |

- gateway 는 WebFlux 라 로그 MDC 가 스레드를 따라가지 않는다. gateway 가 남기는 로그(오류 처리기)는 traceId 를 메시지에 직접 넣는다
- traceId 는 추적용일 뿐이다. 권한·업무 판단에 쓰지 않는다
- **정하지 않은 것**: 서비스끼리의 동기 호출(client/)에 traceId 를 실어 보낼지, 서비스 로그 줄에 traceId 를 찍을지. gateway 만 맡기로 해서
  서비스(order, production 등)는 바꾸지 않았다. 필요하면 각 서비스에서 새 이슈로 정한다 (2026-10-15, #67)

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 | 수신 | payload |
|---|---|---|---|
| order.created | order | production | orderId, products[{productId, qty}] |
| order.cancel-requested | order | production | orderId |
| order.cancelled | order | (없음, 이후 알림·출고) | orderId |
| production.material-requested | production | inventory | productionId, parts[{partId, qty}] |
| production.status-changed | production | order | orderId, productionId, productId, status |
| production.cancelled | production | inventory, order | orderId, productionId |
| production.cancel-rejected | production | order | orderId, productionId, reason (ALREADY_STARTED), status |
| production.completed | production | inventory, order | orderId, productionId, productId, vin |
| inventory.reserved | inventory | production | productionId |
| inventory.reservation-pending | inventory | production | productionId, shortages[{partId, shortQty}] |
| inventory.reservation-failed | inventory | production | productionId, reason (PART_NOT_FOUND), partIds |
| inventory.shortage | inventory | purchase | partId, partCode, partName, shortQty |
| purchase.received | purchase | inventory | purchaseId, partId, qty |
| production.step-dispatched | production | simulator | workOrderId, process, equipmentCode, dispatchSeq |
| equipment.step-started | simulator | production | equipmentCode, workOrderId, process, dispatchSeq |
| equipment.step-completed | simulator | production | equipmentCode, workOrderId, process, result (GOOD, DEFECT), defectReason, dispatchSeq |
| equipment.status-changed | simulator | production | equipmentCode, process, status (RUNNING, STOPPED, FAULT) |
| shipment.completed | inventory | order | orderId, productionId, productId, vin |

## 완료 조건 (테스트로 확인할 것)
하위 이슈마다 나눠 확인한다.
- [ ] 없는 제품 또는 BOM 없는 제품 주문은 404, 주문과 이벤트가 생기지 않는다
- [ ] 같은 차 3대 주문은 생산 건 3건, 예약 요청 3건이 된다
- [ ] inventory.reserved 를 받으면 작업지시 1건과 공정 5개가 라우팅 순서대로 생긴다
- [ ] 공정은 순서대로만, 설비가 RUNNING 일 때만 시작된다
- [ ] 로봇 공정은 production 이 비어 있는 설비에 배정하고 설비 보고로 진행된다. 프레스도 작업지시가 발행되자마자 배정된다
- [ ] 공정 불량은 실적에 남고 같은 공정을 다시 한다. 이벤트는 없다
- [ ] 검사 양품 → VIN 발행, production.completed, 부품 CONSUMED, 완성차 재고 증가, 주문 COMPLETED
- [ ] 재고 부족: WAITING + AUTO 구매, 입고 후 먼저 대기한 순서대로 RESERVED, 작업지시 생성
- [ ] 설비 고장·정지: 그 설비의 공정이 PENDING 으로 돌아가 다른 설비로 재배정, 생산 건·주문 상태 그대로
- [ ] 프레스 배정 전 취소: 예약 해제, 차량 CANCELLED, order.cancelled
- [ ] 프레스 배정 후 취소: cancel-rejected, 차량 상태 복귀
- [ ] 취소 후 늦게 온 inventory.reserved 는 해제로 정리된다
- [ ] 같은 이벤트를 두 번 받아도 재고와 상태가 한 번만 바뀐다
- [ ] part.reserved 는 항상 그 부품의 RESERVED 예약 qty 합과 같다
- [ ] shortQty 는 대기 수량을 포함한 증가분만 계산된다 (예: available 3, 대기 2, 새 요청 2 → 1)
- [ ] 같은 부품 부족이 여러 번 오면 REQUESTED 상태의 AUTO 구매 1건에 수량이 합쳐진다
