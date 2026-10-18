# 생산 건 상태 변경 (inventory 예약 결과 수신)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 4번이다.
production 이 inventory 의 부품 예약 결과를 받아 생산 건 상태를 바꾸고, 바뀐 상태를 `production.status-changed` 로 order 에 알린다.

## 흐름
1. inventory 가 생산 건마다 결과를 하나 발행한다 (docs/specs/inventory-reservation.md)
   - 모두 예약됨 → `inventory.reserved { productionId }`
   - 일부 대기 → `inventory.reservation-pending { productionId, shortages[{partId, shortQty}] }`
2. production 이 큐 `production.inventory-reserved`, `production.inventory-reservation-pending` 으로 받는다
3. `ProcessedEventGuard` 로 같은 이벤트를 한 번만 처리한다. 상태 변경과 이벤트 발행은 처리 기록과 같은 트랜잭션이다
4. 허용된 이전 상태에서만 바꾼다

| 받은 이벤트 | 현재 상태 | 처리 |
|---|---|---|
| inventory.reserved | PLANNED, WAITING_MATERIAL | → **READY** + `production.status-changed` |
| inventory.reserved | CANCELLED | 상태 유지, `production.cancelled { orderId, productionId }` 를 다시 보낸다 (늦게 온 예약을 inventory 가 해제하게) |
| inventory.reserved | READY, IN_PROGRESS, COMPLETED | 무시 (INFO 로그) |
| inventory.reservation-pending | PLANNED | → **WAITING_MATERIAL** + `production.status-changed` |
| inventory.reservation-pending | 그 외 | 무시 (INFO 로그) |

5. `production.status-changed { orderId, productionId, productId, status }` 를 발행한다
   - READY 는 작업지시 발행 대기다. 작업지시와 공정 5개는 생산 담당이 발행할 때 만든다 (docs/specs/work-order-release.md, #131. 그 전에는 이 전이에서 자동으로 만들었다)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 이벤트를 두 번 받음 | 두 번째는 건너뛴다 (ProcessedEventGuard) |
| 허용되지 않은 상태에서 온 이벤트 | 상태를 바꾸지 않고 INFO 로그만 남긴다 (순서가 꼬였거나 이미 처리됨) |
| 없는 생산 건 (productionId) | 처리 중 오류로 본다 (`PRODUCTION_NOT_FOUND`). 재시도 후 DLQ. 정상 흐름에서는 생기지 않는다 |
| 처리 중 오류 | 트랜잭션 롤백, 1s·2s·4s 로 3번 재시도, 그래도 실패하면 `EVENT_DEAD_LETTERED` 로그 후 `{큐}.dlq` |

- traceId: 받은 이벤트의 traceId 를 발행하는 이벤트에 그대로 쓴다 (SAGA "추적")
- 리스너(`event/listener/InventoryEventListener`)는 `ProcessedEventGuard` 로 중복을 거르고 `ProductionService` 를 부른다 (/add-event 수신 규칙)
- 이 작업은 production 의 상태 변경과 발행까지다. order 의 status-changed 수신(SAGA 8번), inventory 의 cancelled 수신(취소 보상)은 다음 작업이다

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| inventory.reserved | inventory → production | productionId |
| inventory.reservation-pending | inventory → production | productionId, shortages[{partId, shortQty}] |
| production.status-changed | production → order | orderId, productionId, productId, status |
| production.cancelled | production → inventory, order | orderId, productionId |

## 완료 조건 (테스트로 확인할 것)
- [ ] PLANNED 생산 건에 reserved → READY, status-changed(READY) 1건
- [ ] WAITING_MATERIAL 생산 건에 reserved → READY, status-changed(READY) 1건 (입고 후 예약)
- [ ] PLANNED 생산 건에 reservation-pending → WAITING_MATERIAL, status-changed(WAITING_MATERIAL) 1건
- [ ] CANCELLED 생산 건에 reserved → 상태 유지, production.cancelled(orderId, productionId) 1건, status-changed 없음
- [ ] 허용되지 않은 상태(READY 에 reservation-pending 등)는 상태도 이벤트도 바뀌지 않는다
- [ ] 같은 이벤트를 두 번 받아도 status-changed 는 한 번만 생긴다
- [ ] 발행한 이벤트의 traceId 는 받은 이벤트의 traceId 와 같다
- [ ] RabbitMQ 로 실제 두 이벤트를 보내면 처리된다, 없는 생산 건은 DLQ 로 간다 (Testcontainers)
