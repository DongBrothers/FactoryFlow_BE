# 출고 (완성차 출고 → shipment.completed → 차량 SHIPPED)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 11번이다 (#77).
창고 담당자가 완성차 한 대를 출고하면 inventory 가 완성차 재고를 SHIPPED 로 바꾸고 `shipment.completed` 를 발행한다.
order 는 그 차량을 SHIPPED 로 바꾸고 품목·주문 상태를 다시 요약한다.

- **출고 주체는 inventory 다.** 완성차 재고(`finished_vehicle`, VIN 단위, IN_STOCK/SHIPPED)를 inventory 가 이미 갖고 있어서,
  출고 = 그 재고 한 대를 내보내는 것이다. order 는 다른 이벤트처럼 결과만 받는다
- 출고 단위는 차량 1대(VIN)다. 주문의 차량을 나눠 출고할 수 있다 (일부만 출고된 주문은 COMPLETED, 모두 출고되면 SHIPPED. 요약 규칙 그대로)
- 운송·배송 추적, 출고 취소(되돌리기), 여러 대 한 번에 출고, 역할별 권한은 넣지 않는다

## 흐름

### 1. 출고 (inventory, 동기) `POST /inventory/finished-vehicles/{vin}/ship`
gateway 경로는 `/api/inventory/finished-vehicles/{vin}/ship`. 요청 본문 없음.
| 확인 (이 순서로) | 실패 |
|---|---|
| vin 이 VIN 형식 (17자, 영문 대문자·숫자, I·O·Q 없음. `@Pattern`) | 400 |
| 완성차 있음 | 404 `FINISHED_VEHICLE_NOT_FOUND` |
| 아직 출고 안 됨 (IN_STOCK) | 409 `FINISHED_VEHICLE_ALREADY_SHIPPED` |
| 저장 때 다른 요청이 먼저 바꾸지 않음 (완성차 version) | 409 `FINISHED_VEHICLE_CONFLICT` |

통과하면 한 트랜잭션에서
- 완성차 IN_STOCK → SHIPPED, `shipped_at` = 지금
- `shipment.completed { orderId, productionId, vin }` (Outbox)
- 응답 200
```json
{ "vin": "KMH00001?TU000012", "productId": 1, "status": "SHIPPED", "shippedAt": "2026-10-16T21:00:00", "slotCode": "A-01" }
```
내부 id(생산 건 id, 주문 id)는 내보내지 않는다 (주문 상세 조회와 같은 기준).

완성차 재고 감소 = IN_STOCK 인 완성차가 한 대 줄어드는 것이다. stock_history 는 부품 재고 이력이라 쓰지 않는다.

### 2. 차량 출고 반영 (order ← shipment.completed)
큐 `order.shipment-completed`, ProcessedEventGuard, 한 트랜잭션.
- 그 차량(productionId)을 SHIPPED 로 바꾸고, VIN 이 비어 있으면 기록한다
- 바뀌었으면 품목 → 주문 상태를 다시 요약한다 (요약 규칙 3: 모두 SHIPPED → SHIPPED, 4: COMPLETED·SHIPPED 섞임 → COMPLETED)

차량 상태별 처리
| 차량 상태 | 처리 |
|---|---|
| COMPLETED | SHIPPED |
| RECEIVED, WAITING_MATERIAL, IN_PRODUCTION | SHIPPED + VIN 기록. 큐가 달라 `production.completed` 보다 출고가 먼저 처리될 수 있다. 출고됐다는 것은 생산이 끝났다는 뜻이다 |
| CANCEL_REQUESTED | SHIPPED. order 가 늦은 상태로 취소를 받았어도 완성된 차는 production 이 반드시 거절한다. 나중에 온 거절은 요청 중이 아닌 차량이라 무시된다 (지금 규칙) |
| SHIPPED | 그대로 (INFO) |
| CANCELLED | 그대로 (WARN). 취소된 차는 완성되지 않으므로 정상 흐름에서는 생기지 않는다 |
| 처음 보는 차량 | `production.completed` 처럼 품목에 차량을 만들고 SHIPPED 로 한다. 품목 수량을 넘으면 `ORDER_VEHICLE_OVERFLOW` → DLQ |

품목을 찾는 데 productId 가 필요하므로, 처음 보는 차량을 만들려면 payload 에 productId 가 있어야 한다 → 아래 "관련 이벤트"

늦게 온 `production.completed` 는 SHIPPED 차량을 바꾸지 않는다 (끝난 상태는 그대로, 지금 규칙).

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 차를 두 번 출고 요청 | 두 번째는 409 `FINISHED_VEHICLE_ALREADY_SHIPPED`, 이벤트 한 번 |
| 같은 차를 동시에 출고 요청 | version 으로 한쪽만 반영, 늦은 쪽 409 `FINISHED_VEHICLE_CONFLICT` |
| 삭제된 완성차 | 404 `FINISHED_VEHICLE_NOT_FOUND` |
| 같은 shipment.completed 를 두 번 받음 (order) | Guard 로 한 번. 다른 eventId 면 이미 SHIPPED 라 그대로 |
| 없는 주문·품목 (order) | 처리 오류 `ORDER_ITEM_NOT_FOUND` → 재시도 → DLQ (production.completed 와 같음) |
| 같은 주문의 차량들이 동시에 바뀜 (order) | 품목·주문 version 으로 늦은 쪽이 충돌 → 재시도 (지금 규칙) |
| 출고된 차량이 있는 주문의 취소 요청 | 409 `ORDER_CANNOT_BE_CANCELLED` (지금 규칙) |

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| inventory (V4) | `finished_vehicle.shipped_at` (DATETIME(6) NULL), `finished_vehicle.version` (BIGINT NOT NULL DEFAULT 0) |
| order | 없음 (SHIPPED 상태와 vin 컬럼은 이미 있다) |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| shipment.completed | inventory → order (`order.shipment-completed`) | orderId, productionId, **productId**, vin |

events.md 의 `shipment.completed` 행: 발행 `inventory`, payload 에 productId 를 더한다 (처음 보는 차량의 품목을 찾기 위해. 아직 발행한 적 없는 이벤트라 version 1 그대로).

## 완료 조건 (테스트로 확인할 것)
inventory
- [ ] 출고: 완성차 SHIPPED + shipped_at, shipment.completed outbox (orderId, productionId, productId, vin). 응답에 내부 id 없음
- [ ] 400 VIN 형식, 404 없는·삭제된 완성차, 409 이미 출고됨 (이벤트 추가 없음)
- [ ] 동시 출고는 한쪽만 반영, 늦은 쪽 409 FINISHED_VEHICLE_CONFLICT
- [ ] 받은 요청의 traceId 를 이어 쓴다
- [ ] V4 마이그레이션 (shipped_at, version)
order
- [ ] shipment.completed 수신 → 차량 SHIPPED, 모두 출고면 품목·주문 SHIPPED, 일부면 COMPLETED
- [ ] 생산 완료를 아직 못 받은 차량·취소 요청 중 차량도 SHIPPED (+ VIN), 뒤에 온 production.completed·cancel-rejected 는 바꾸지 않음
- [ ] 취소된 차량·이미 출고된 차량은 그대로
- [ ] 처음 보는 차량은 만들어 출고 처리, 없는 품목은 DLQ
- [ ] 같은 이벤트 두 번 → 한 번만, 실제 브로커 경유 수신
