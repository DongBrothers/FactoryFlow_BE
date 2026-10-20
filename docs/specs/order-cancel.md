# 주문 취소 (order.cancel-requested → 차량별 판단 → order.cancelled) + 주문 상세 조회

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) "4. 취소 (보상)" 다 (#75).
고객이 주문 취소를 요청하면 order 가 차량을 "취소 요청 중" 으로 바꾸고 production 에 알린다.
production 은 차량(생산 건)마다 판단한다: 프레스 배정 전이면 취소하고 부품 예약을 풀게 하고, 이미 배정됐으면 거절한다. 작업지시를 발행한 차는 시작 여부를 아는 mes 가 판단한다 (docs/specs/mes-split.md "4. 주문 취소").
order 는 차량마다 답을 반영하고, 답이 다 오면 이번 요청으로 취소된 차가 있을 때 `order.cancelled` 를 보낸다.
취소 결과를 볼 수 있도록 주문 상세 조회 API 도 함께 만든다 (지금은 주문 상태를 볼 API 가 없다).

- 첫 공정(프레스) 배정이 되돌릴 수 없는 지점이다 (생산 순서 확정). 프레스 대기 시간이 없으므로(#90) 부품이 다 잡힌 차는 다음 배정 주기에 배정된다. 그래서 실제로 취소가 승인되는 것은 대개 부품을 기다리는 차다
- 취소는 주문 전체 단위로 요청하고, 판단은 차량마다 한다. 일부 차만 취소될 수 있다
- 이미 들어간 자동 구매는 취소하지 않는다 (재고로 남는다)

## 흐름

### 1. 취소 요청 (order, 동기) `POST /orders/{orderNo}/cancel`
| 확인 (이 순서로) | 실패 |
|---|---|
| orderNo 가 UUID 형식 | 400 |
| 주문 있음 | 404 `ORDER_NOT_FOUND` |
| 차량을 모두 안다 (품목 수량 합 = 알려진 차량 수). 아직 생산 계획 중이면 거절 | 409 `ORDER_NOT_READY` ("잠시 후 다시") |
| 완료(COMPLETED)·출고(SHIPPED)된 차량이 없다 | 409 `ORDER_CANNOT_BE_CANCELLED` |
| 이미 취소 요청 중(CANCEL_REQUESTED)인 차량이 없다 | 409 `CANCEL_IN_PROGRESS` |
| 취소할 차량(CANCELLED 가 아닌 것)이 하나라도 있다 | 409 `ORDER_ALREADY_CANCELLED` |
| 저장 때 다른 처리가 먼저 바꾸지 않음 (주문 version) | 409 `ORDER_CONFLICT` |

통과하면 한 트랜잭션에서
- CANCELLED 가 아닌 차량을 모두 CANCEL_REQUESTED 로, 품목·주문 상태를 다시 요약 (요약 규칙 2: 하나라도 CANCEL_REQUESTED → CANCEL_REQUESTED)
- `customer_order.cancel_requested_at` = 지금 (이번 요청 회차)
- `order.cancel-requested { orderId }` (Outbox)
- 응답 200: 주문 상세 (아래 3)

order 가 가진 차량 상태는 늦을 수 있으므로 취소 가능 여부의 최종 판단은 production 이 한다 (order 는 READY 로 아는데 이미 프레스가 배정됐을 수 있다).

### 2. 차량별 판단 (production ← order.cancel-requested)
큐 `production.order-cancel-requested`, ProcessedEventGuard, 한 트랜잭션.
1. 그 주문의 생산 건을 모두 읽는다. 하나도 없으면 처리 오류 `PRODUCTION_NOT_FOUND` → 재시도 → DLQ (order 가 차량을 다 알 때만 요청하므로 정상 흐름에서는 생기지 않음)
2. 생산 건마다
   | 생산 건 상태 | 처리 |
   |---|---|
   | PLANNED, WAITING_MATERIAL, 발행 전 READY | 생산 건 CANCELLED + `production.cancelled { orderId, productionId }` |
   | 발행한 READY (`released_at` 있음) | 상태 그대로 + `production.release-cancel-requested` → mes 가 첫 공정 전이면 작업지시 CANCELLED + `mes.work-order-cancelled` (production 이 생산 건 CANCELLED + `production.cancelled`), 시작했으면 `mes.work-order-cancel-rejected` (production 이 `production.cancel-rejected`, status = 작업지시 상태) |
   | IN_PROGRESS, COMPLETED (프레스 배정 후) | 상태 그대로 + `production.cancel-rejected { orderId, productionId, reason: ALREADY_STARTED, status }` (status = 지금 생산 건 상태) |
   | CANCELLED (이미 취소됨) | `production.cancelled` 를 다시 보낸다 (order·inventory 는 여러 번 받아도 안전) |
3. 발행한 차의 취소 요청과 프레스 배정이 mes 에서 동시에 오면 작업지시 version 으로 한쪽만 반영된다. 리스너가 지면 재시도 때 최신 상태로 다시 판단한다 (배정이 이겼으면 거절)
4. 취소된 작업지시는 배정 대상이 아니고 작업자 API 는 409 `WORK_ORDER_CLOSED` (지금 규칙)

### 3. 예약 해제 (inventory ← production.cancelled)
큐 `inventory.production-cancelled`, ProcessedEventGuard, 한 트랜잭션.
1. 그 생산 건의 예약 줄 중
   - RESERVED → RELEASED: available +qty, reserved −qty, stock_history RELEASE (ref PRODUCTION)
   - WAITING → RELEASED: 재고 변화 없음
   - RELEASED, CONSUMED 는 그대로 (여러 번 받아도 안전)
2. 풀린 부품마다 그 부품을 기다리는 줄을 먼저 온 순서로 채운다 (입고 때와 같은 규칙: 앞 줄이 안 들어가면 멈춤). 줄이 다 찬 생산 건은 `inventory.reserved` (docs/specs/purchase-receiving.md "3. 입고" 와 같은 처리)
3. 예약 줄이 아직 없으면(부품 요청보다 취소가 먼저 처리됨) 아무것도 하지 않는다. 나중에 예약되면 production 이 CANCELLED 생산 건에 온 inventory.reserved 로 `production.cancelled` 를 다시 보내 풀게 한다 (지금 규칙)

### 4. 답 반영 (order ← production.cancelled / production.cancel-rejected)
큐 `order.production-cancelled`, `order.production-cancel-rejected`, ProcessedEventGuard, 한 트랜잭션.
- cancelled: 차량 CANCELLED, `order_vehicle.cancelled_at` = 지금
- cancel-rejected: 취소 요청 중인 차량을 payload 의 status 로 되돌린다 (매핑은 status-changed 와 같음: IN_PROGRESS → IN_PRODUCTION, COMPLETED → COMPLETED). 요청 중이 아니면 그대로
- 품목·주문 상태를 다시 요약한다
- 그 주문에 CANCEL_REQUESTED 차량이 더 없고, 이번 요청 회차에 취소된 차량(cancelled_at ≥ cancel_requested_at)이 하나라도 있으면 `order.cancelled { orderId }` (한 회차에 한 번)

### 5. 주문 상세 조회 (order) `GET /orders/{orderNo}`
```json
{ "orderNo": "…", "customerId": 7, "status": "IN_PRODUCTION",
  "items": [ { "productId": 1, "productCode": "AVANTE", "productName": "아반떼", "qty": 2, "status": "IN_PRODUCTION",
               "vehicles": [ { "status": "IN_PRODUCTION", "vin": null }, { "status": "COMPLETED", "vin": "KMH…" } ] } ] }
```
- 내부 id(주문 id, 생산 건 id)는 내보내지 않는다. 차량은 그 품목 안에서 생산 건 id 순서
- orderNo 형식 400, 없으면 404 `ORDER_NOT_FOUND`
- 아직 알려지지 않은 차량은 목록에 없다 (품목 qty 와 차량 수가 다를 수 있음)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 cancel-requested 를 두 번 받음 | Guard 로 한 번. 다른 eventId 면 이미 CANCELLED 인 건은 cancelled 재발행, 나머지는 그대로 다시 판단 (같은 결과) |
| 같은 cancelled / cancel-rejected 를 두 번 받음 (order) | Guard 로 한 번. 이미 CANCELLED 거나 요청 중이 아닌 차량은 그대로. order.cancelled 는 회차당 한 번 |
| 취소 요청 중에 온 status-changed (order) | CANCELLED 외에는 무시 (지금 규칙). 거절이 오면 그때의 status 로 되돌린다 |
| 취소된 생산 건에 늦게 온 예약 완료 (production) | production.cancelled 재발행 (지금 규칙) → inventory 가 푼다 |
| 부분 취소 뒤 다시 취소 요청 | 받는다. 남은 차량만 CANCEL_REQUESTED, 새 회차 |
| 모든 차량이 취소된 주문 | 주문 CANCELLED (요약 규칙 1). 다시 요청하면 409 `ORDER_ALREADY_CANCELLED` |

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| order (V6) | `customer_order.cancel_requested_at` (DATETIME(6) NULL), `order_vehicle.cancelled_at` (DATETIME(6) NULL) |
| production, inventory | 없음 |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| order.cancel-requested | order → production (`production.order-cancel-requested`) | orderId |
| production.cancelled | production → inventory (`inventory.production-cancelled`), order (`order.production-cancelled`) | orderId, productionId |
| production.cancel-rejected | production → order (`order.production-cancel-rejected`) | orderId, productionId, reason (ALREADY_STARTED), **status** |
| order.cancelled | order → (없음, 이후 알림) | orderId |
`production.cancel-rejected` 에 status 를 더한다 (필드 추가, events.md 사람 적용). reason 의 NOT_READY 는 쓰지 않는다 (order 가 입구에서 409 로 거절).

## 완료 조건 (테스트로 확인할 것)
order
- [ ] 취소 요청: 차량·품목·주문 CANCEL_REQUESTED, cancel_requested_at, order.cancel-requested outbox. 응답은 주문 상세
- [ ] 409: 차량을 다 모름, 완료·출고 차량 있음, 이미 취소 요청 중, 모두 취소됨. 404 없는 주문, 400 orderNo 형식
- [ ] cancelled → 차량 CANCELLED, cancel-rejected → payload status 로 복귀, 요약 갱신
- [ ] 답이 다 오고 이번 회차에 취소된 차가 있으면 order.cancelled 한 번, 모두 거절이면 보내지 않음
- [ ] 같은 답 두 번 → 한 번만, 실제 브로커 경유 수신
- [ ] 주문 상세 조회: 품목·차량 상태와 VIN, 내부 id 없음, 404·400
production
- [ ] 발행 전 생산 건 → CANCELLED + production.cancelled, 발행한 차 → release-cancel-requested (mes 판단), 시작 후 → cancel-rejected(ALREADY_STARTED, status)
- [ ] 이미 취소된 생산 건 → cancelled 재발행, 생산 건이 없으면 DLQ, 같은 메시지 두 번 → 한 번만
- [ ] 취소된 작업지시는 배정되지 않는다
inventory
- [ ] cancelled → RESERVED 줄 RELEASED(available +, reserved −, RELEASE 이력), WAITING 줄 RELEASED, part.reserved 합계 유지
- [ ] 풀린 부품으로 기다리던 줄을 먼저 온 순서로 채우고 다 찬 생산 건은 inventory.reserved
- [ ] 두 번 받아도 한 번만, 예약 줄이 없으면 아무것도 안 함
