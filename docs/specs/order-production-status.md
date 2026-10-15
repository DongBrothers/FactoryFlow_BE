# 주문 품목 상태 갱신 (production.status-changed 수신)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 8번 중 status-changed 부분이다.
order 가 생산 건 상태 변화를 받아 주문 품목(order_item) 상태를 바꾸고, 주문(customer_order) 상태 요약을 같은 트랜잭션에서 다시 계산한다.
고객이 주문을 조회하면 "부품 대기", "생산 중" 같은 진행 상태를 볼 수 있게 된다.

## 흐름
1. production 이 생산 건 상태가 바뀔 때마다 발행한다: `production.status-changed { orderId, productionId, productId, status }`
   (docs/specs/production-reservation-result.md. 불량 재생산 중 변화는 보내지 않는다)
2. order 가 큐 `order.production-status-changed` 로 받는다
3. `ProcessedEventGuard.runOnce(envelope, …)` 로 같은 이벤트를 한 번만 처리하고 traceId 를 이어 받는다
4. `(orderId, productId)` 로 주문 품목을 찾아 상태를 바꾼다 (SAGA "상태 매핑")

| production.status | order_item.status |
|---|---|
| PLANNED | RECEIVED |
| WAITING_MATERIAL | WAITING_MATERIAL |
| READY, IN_PROGRESS | IN_PRODUCTION |
| COMPLETED | COMPLETED |
| CANCELLED | CANCELLED |

5. 같은 트랜잭션에서 그 주문의 모든 품목으로 customer_order.status 를 다시 계산한다 (SAGA "customer_order.status 요약")
   CANCELLED 품목은 빼고 남은 품목으로, 위에서부터 먼저 맞는 것
   1. 모든 품목이 CANCELLED → CANCELLED
   2. 하나라도 CANCEL_REQUESTED → CANCEL_REQUESTED
   3. 남은 품목이 모두 COMPLETED → COMPLETED
   4. 하나라도 IN_PRODUCTION 또는 COMPLETED → IN_PRODUCTION
   5. 하나라도 WAITING_MATERIAL → WAITING_MATERIAL
   6. 그 외 → RECEIVED

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 이벤트를 두 번 받음 | 두 번째는 건너뛴다 (ProcessedEventGuard) |
| 품목이 COMPLETED 또는 CANCELLED (끝난 상태) | 바꾸지 않고 INFO 로그 (늦게 온 이벤트) |
| 품목이 CANCEL_REQUESTED (취소 결과 대기) | CANCELLED 만 반영하고, 그 외 상태는 무시한다 (취소 결과는 cancelled / cancel-rejected 로 정해진다) |
| 매핑 결과가 지금 품목 상태와 같음 (예: READY → IN_PROGRESS 둘 다 IN_PRODUCTION) | 바꾸지 않는다. 요약도 그대로 |
| 없는 주문 품목 (orderId, productId) 또는 모르는 status 값 | 처리 중 오류 (`ORDER_ITEM_NOT_FOUND`, `UNKNOWN_PRODUCTION_STATUS`). 재시도 후 DLQ. 정상 흐름에서는 생기지 않는다 |
| 처리 중 오류 | 트랜잭션 롤백, 1s·2s·4s 로 3번 재시도, 그래도 실패하면 `EVENT_DEAD_LETTERED` 로그 후 `order.production-status-changed.dlq` |

- order 는 이 처리에서 이벤트를 발행하지 않는다
- production.completed 수신(COMPLETED), cancel-rejected 수신, 고객 취소 요청은 다음 작업이다. 이 작업의 상태 매핑과 요약 계산을 그때 그대로 쓴다

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| production.status-changed | production → order | orderId, productionId, productId, status |

## 완료 조건 (테스트로 확인할 것)
- [ ] status-changed(WAITING_MATERIAL) → 품목 WAITING_MATERIAL, 주문 WAITING_MATERIAL
- [ ] status-changed(READY) → 품목 IN_PRODUCTION, 주문 IN_PRODUCTION
- [ ] 품목 2개 중 하나 READY, 하나 WAITING_MATERIAL → 품목 상태가 각각 다르고 주문은 IN_PRODUCTION
- [ ] 끝난 품목(COMPLETED, CANCELLED)에 온 status-changed 는 무시한다
- [ ] CANCEL_REQUESTED 품목에는 CANCELLED 만 반영된다
- [ ] 요약 규칙 6가지를 각각 확인한다 (단위 테스트)
- [ ] 같은 이벤트를 두 번 받아도 한 번만 반영된다
- [ ] RabbitMQ 로 실제 이벤트를 보내면 처리되고, 없는 품목은 DLQ 로 간다 (Testcontainers)
