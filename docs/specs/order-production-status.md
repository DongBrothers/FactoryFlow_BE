# 차량·품목·주문 상태 갱신 (production.status-changed 수신)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 10번 중 status-changed 부분이다.
생산 건은 차량 1대 단위다 (기획서). order 는 차량(생산 건)마다 상태를 갖고, 품목 상태는 그 품목 차량들의 요약,
주문(customer_order) 상태는 품목들의 요약이다. 셋을 같은 트랜잭션에서 갱신한다.
고객이 주문을 조회하면 "접수", "생산 중" 같은 진행 상태를 볼 수 있게 된다. 부품 대기는 고객에게 따로 보이지 않고 접수로 보인다 (#117).

## 흐름
1. production 이 생산 건이 생길 때(PLANNED)와 상태가 바뀔 때마다 발행한다: `production.status-changed { orderId, productionId, productId, status }`
2. order 가 큐 `order.production-status-changed` 로 받는다
3. `ProcessedEventGuard.runOnce(envelope, …)` 로 같은 이벤트를 한 번만 처리하고 traceId 를 이어 받는다
4. productionId 로 차량(`order_vehicle`)을 찾는다. 처음 보는 생산 건이면 `(orderId, productId)` 품목에 차량을 새로 만든다 (RECEIVED)
5. 차량 상태를 바꾼다 (SAGA "상태 매핑")

| production.status | 차량 상태 |
|---|---|
| PLANNED, WAITING_MATERIAL, READY | RECEIVED (부품 대기는 주문 쪽 상태가 아니다 #117, READY 는 작업지시 발행 대기 #131) |
| IN_PROGRESS | IN_PRODUCTION |
| COMPLETED | COMPLETED |
| CANCELLED | CANCELLED |

6. 같은 트랜잭션에서 품목 상태(그 품목의 차량들)와 주문 상태(그 주문의 품목들)를 다시 계산한다 (SAGA "요약")
   - 품목 수량보다 아직 알려지지 않은 차량이 있으면 그 차량은 RECEIVED 로 보고 계산한다
   - CANCELLED 는 빼고 남은 것으로, 위에서부터 먼저 맞는 것
   1. 남은 것이 없음 → CANCELLED
   2. 하나라도 CANCEL_REQUESTED → CANCEL_REQUESTED
   3. 모두 SHIPPED → SHIPPED
   4. 모두 COMPLETED 또는 SHIPPED → COMPLETED
   5. 하나라도 IN_PRODUCTION, COMPLETED, SHIPPED → IN_PRODUCTION
   6. 그 외 → RECEIVED (#117 에서 "하나라도 WAITING_MATERIAL → WAITING_MATERIAL" 을 없앰)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 이벤트를 두 번 받음 | 두 번째는 건너뛴다 (ProcessedEventGuard) |
| 차량이 COMPLETED, SHIPPED, CANCELLED (끝난 상태) | 바꾸지 않고 INFO 로그 (늦게 온 이벤트) |
| 차량이 CANCEL_REQUESTED (취소 결과 대기) | CANCELLED 만 반영하고, 그 외 상태는 무시한다 (취소 결과는 cancelled / cancel-rejected 로 정해진다) |
| 매핑 결과가 지금 차량 상태와 같음 (예: READY → IN_PROGRESS 둘 다 IN_PRODUCTION) | 바꾸지 않는다. 요약도 그대로 |
| 처음 보는 생산 건인데 그 품목의 차량이 이미 수량만큼 있음 | 처리 중 오류 (`ORDER_VEHICLE_OVERFLOW`). 정상 흐름에서는 생기지 않는다 |
| 없는 주문 품목 (orderId, productId), 품목의 주문이 없음, 모르는 status 값 | 처리 중 오류 (`ORDER_ITEM_NOT_FOUND`, `ORDER_NOT_FOUND`, `UNKNOWN_PRODUCTION_STATUS`). 재시도 후 DLQ. 정상 흐름에서는 생기지 않는다 |
| 이 변경(#117) 전에 이미 WAITING_MATERIAL 인 주문·품목·차량 | V7 마이그레이션이 RECEIVED 로 바꾼다. 없는 상태 값이 남으면 조회가 깨진다 |
| 처리 중 오류 | 트랜잭션 롤백, 1s·2s·4s 로 3번 재시도, 그래도 실패하면 `EVENT_DEAD_LETTERED` 로그 후 `order.production-status-changed.dlq` |

- order 는 이 처리에서 이벤트를 발행하지 않는다
- production.completed / cancelled 수신, 출고, 고객 취소 요청은 해당 하위 이슈에서 한다. 이 작업의 차량 상태와 요약 계산을 그대로 쓴다
- 테이블 `order_vehicle (id, order_id, order_item_id, production_id UNIQUE, status)` (Flyway)

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| production.status-changed | production → order | orderId, productionId, productId, status |

## 완료 조건 (테스트로 확인할 것)
- [ ] status-changed(PLANNED) 를 처음 받으면 차량이 RECEIVED 로 생긴다
- [ ] 2대 품목에서 한 대만 READY → 차량 IN_PRODUCTION, 품목·주문 IN_PRODUCTION
- [ ] 2대 품목에서 한 대만 WAITING_MATERIAL, 나머지는 아직 모름 → 품목 RECEIVED (#117)
- [ ] 품목 2개 중 하나 READY, 하나 WAITING_MATERIAL → 품목 상태가 각각 다르고 주문은 IN_PRODUCTION
- [ ] 끝난 차량(COMPLETED, CANCELLED)에 온 status-changed 는 무시한다
- [ ] CANCEL_REQUESTED 차량에는 CANCELLED 만 반영된다
- [ ] 요약 규칙 6가지를 각각 확인한다 (단위 테스트)
- [ ] 2대 품목에서 아는 한 대가 COMPLETED, 나머지는 아직 모름 → 품목 IN_PRODUCTION (모르는 차는 RECEIVED 로 셈)
- [ ] V7: 이미 WAITING_MATERIAL 인 주문·품목·차량 → RECEIVED, 다른 상태는 그대로 (#117)
- [ ] 같은 이벤트를 두 번 받아도 한 번만 반영된다
- [ ] RabbitMQ 로 실제 이벤트를 보내면 처리되고, 없는 품목은 DLQ 로 간다 (Testcontainers)
