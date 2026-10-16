# 생산 완료 (검사 양품 → VIN 발행 → production.completed)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 8~10번이다 (#73).
검사(INSPECTION)를 양품으로 끝내면 production 이 차대번호(VIN)를 발행하고 작업지시·생산 건을 COMPLETED 로 바꾼 뒤 `production.completed` 를 발행한다.
inventory 는 그 차에 잡아 둔 부품을 사용 처리(CONSUMED)하고 완성차 재고를 1대 늘린다. order 는 차량을 COMPLETED 로 바꾸고 품목·주문 상태를 다시 요약한다.
부품 LOT ↔ VIN 연결(#76), 출고(#77)는 이 작업에 넣지 않는다.

## 흐름
1. 작업자(또는 설비)가 검사 공정을 GOOD 으로 끝낸다 (`WorkOrderService.completeStep` / `reportStepCompleted` 의 공통 `finishStep`)
2. 같은 트랜잭션에서 (production)
   - 실적 GOOD + 공정 DONE + 설비 비움 (지금과 같음)
   - 그 공정이 마지막 공정(INSPECTION)이면 완료 처리:
     - VIN 발행 (아래 "VIN 형식")
     - 작업지시 IN_PROGRESS → COMPLETED (vin, completed_at)
     - 생산 건 IN_PROGRESS → COMPLETED (completed_at)
     - `production.completed { orderId, productionId, productId, vin }` 발행
   - 동시 변경은 지금처럼 version 으로 막는다 (작업자 API 는 409 `PRODUCTION_CONFLICT`)
3. inventory 가 큐 `inventory.production-completed` 로 받는다 (ProcessedEventGuard, 아래는 같은 트랜잭션)
   - 그 생산 건의 RESERVED 예약 줄을 모두 CONSUMED 로 바꾼다. 부품마다 reserved −qty (available 은 그대로), stock_history CONSUME (ref PRODUCTION, productionId)
   - 완성차 재고 1줄: `finished_vehicle { vin, productionId, orderId, productId, status IN_STOCK }`
   - 이벤트는 발행하지 않는다
4. order 가 큐 `order.production-completed` 로 받는다 (ProcessedEventGuard)
   - 그 차량을 COMPLETED 로 바꾸고 VIN 을 기록한다. 바뀌었으면 같은 트랜잭션에서 품목 → 주문 상태를 다시 요약한다 (요약 규칙은 order-production-status.md 그대로)
   - 모든 차량이 COMPLETED(또는 SHIPPED)면 품목·주문 COMPLETED. 일부만이면 IN_PRODUCTION

`production.status-changed(COMPLETED)` 는 따로 보내지 않는다. order 는 `production.completed` 하나로 완료를 안다.

## VIN 형식
17자, ISO 3779 구조를 따르되 값은 단순하게 정한다. I, O, Q 는 쓰지 않는다.
| 자리 | 길이 | 값 | 예 |
|---|---|---|---|
| 1~3 WMI (제조사) | 3 | `KMH` 고정 (현대) | KMH |
| 4~8 VDS (차종) | 5 | 제품 id 를 5자리 숫자로 (앞을 0 으로 채움) | 00001 |
| 9 체크 자리 | 1 | 북미 VIN 체크 자리 계산 (가중치 합 mod 11, 10 이면 X) | 7 |
| 10 연식 | 1 | 완료 연도 코드 (2026 → T, 2027 → V …, I·O·Q·U·Z·0 제외 표준 표) | T |
| 11 공장 | 1 | `U` 고정 (울산 가정) | U |
| 12~17 일련번호 | 6 | 작업지시 id 를 6자리 숫자로 | 000012 |

예: 제품 1, 작업지시 12, 2026년 완료 → `KMH00001?TU000012` (? = 체크 자리)
- 작업지시 id 가 일련번호라 VIN 은 겹치지 않는다. 그래도 `work_order.vin` 에 UNIQUE 를 건다
- 작업지시 id 가 999999 를 넘으면 처리 오류 (지금 범위에서는 생기지 않음)
- VIN 생성은 순수 함수(`VinGenerator` 같은 domain 클래스)로 두고 단위 테스트한다

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 검사가 아닌 공정을 GOOD 으로 끝냄 | 지금과 같음 (완료 처리 없음) |
| 검사를 DEFECT 로 끝냄 | 지금과 같음 (실적 + PENDING, 재작업). 완료 처리 없음 |
| 검사 GOOD 인데 생산 건이 IN_PROGRESS 가 아님 (정상 흐름에선 안 생김) | 작업자 API 는 409 `WORK_ORDER_CLOSED`, 설비 보고는 처리 오류 → 재시도 → DLQ |
| production.completed 를 두 번 받음 (inventory) | 같은 eventId 는 Guard. 다른 eventId 면 finished_vehicle(production_id UNIQUE)이 이미 있으면 건너뜀 (INFO) |
| 그 생산 건에 RESERVED 줄이 없음 (inventory) | 처리 오류 `RESERVATION_NOT_FOUND` → 재시도 → DLQ. 완료된 차는 반드시 부품이 다 잡혀 있었다 |
| production.completed 를 두 번 받음 (order) | 같은 eventId 는 Guard. 이미 COMPLETED 면 바꾸지 않음 (INFO) |
| 끝난 차량(CANCELLED, FAILED)에 completed 가 옴 (order) | 바꾸지 않음 (INFO). 끝난 상태는 되돌리지 않는다 |
| 없는 품목·차량 (order) | 처리 오류 → DLQ (status-changed 와 같음) |
| 같은 주문의 차량들이 동시에 바뀜 (order) | `order_item`, `customer_order` 에 version 을 추가해 늦은 쪽이 충돌 → 재시도로 최신 상태를 보고 다시 요약한다 (status-changed 큐와 completed 큐가 동시에 같은 품목을 바꿀 수 있다) |

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| production | `work_order.vin` UNIQUE 추가 (컬럼은 이미 있음) |
| inventory | `finished_vehicle` 새 테이블: id, vin (UNIQUE), production_id (UNIQUE), order_id, product_id, status (IN_STOCK, SHIPPED), created_at, updated_at, is_deleted |
| order | `order_vehicle.vin` (VARCHAR 17, NULL) 추가. `customer_order.version`, `order_item.version` (BIGINT NOT NULL DEFAULT 0) 추가 |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| production.completed | production → inventory (`inventory.production-completed`), order (`order.production-completed`) | orderId, productionId, productId, vin |
events.md 에 이미 있다 (변경 없음).

## 완료 조건 (테스트로 확인할 것)
production
- [ ] 검사 GOOD (작업자) → VIN 17자 발행, 작업지시·생산 건 COMPLETED(completed_at), production.completed outbox
- [ ] 검사 GOOD (설비 보고) 도 같은 완료 처리
- [ ] 검사 DEFECT, 검사가 아닌 공정 GOOD 은 완료 처리 없음
- [ ] VIN: 형식(17자, I·O·Q 없음), 체크 자리, 연식 코드, 제품 id·작업지시 id 반영, 자리를 넘는 값 거절 (단위 테스트)
- [ ] 검사 GOOD 인데 생산 건이 IN_PROGRESS 가 아니면 409 WORK_ORDER_CLOSED, 아무것도 바뀌지 않음
- [ ] work_order.vin UNIQUE (마이그레이션 테스트)
- [ ] 받은 요청의 traceId 를 이어 쓴다
inventory
- [ ] completed 수신 → 그 생산 건 RESERVED 줄 모두 CONSUMED, reserved −qty, available 그대로, stock_history CONSUME, finished_vehicle 1줄
- [ ] 같은 이벤트 두 번 / 다른 eventId 재발행 → 한 번만 반영
- [ ] RESERVED 줄이 없으면 DLQ
order
- [ ] completed 수신 → 차량 COMPLETED + VIN, 모든 차량 완료면 품목·주문 COMPLETED, 일부면 IN_PRODUCTION
- [ ] 끝난 차량·이미 COMPLETED 인 차량은 그대로
- [ ] 같은 이벤트 두 번 → 한 번만 반영
- [ ] 품목을 읽은 뒤 다른 트랜잭션이 같은 품목을 먼저 바꿨으면 요약 저장이 version 충돌로 실패한다 (리스너는 재시도로 다시 요약)
