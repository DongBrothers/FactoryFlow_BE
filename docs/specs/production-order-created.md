# 생산 건 생성 (order.created 수신)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 2번이다.
production 이 `order.created` 를 받아 품목 수량만큼 생산 건(**차량 1대씩**)을 만들고, 차량마다 BOM 으로 필요한 부품을 계산해
inventory 에 `production.material-requested` 로 부품 예약을 요청한다 (기획서: 차량 1대 = 생산 건 1건).

## 흐름
1. order 가 `order.created` 를 발행한다: `{ orderId, products: [{ productId, qty }] }`
2. production 이 큐 `production.order-created`(exchange `factoryflow.events`, 라우팅 키 `order.created`)로 받는다
3. `ProcessedEventGuard` 로 같은 이벤트(eventId)를 한 번만 처리한다. 아래 4~5 는 처리 기록과 같은 트랜잭션이다
4. 제품마다 수량만큼 생산 건을 만든다: `production (order_id, product_id, seq = 1..qty, status = PLANNED)`. seq 는 그 주문·제품 안에서 차량 번호다
5. 생산 건마다 삭제되지 않은 BOM 으로 필요한 부품을 계산해 `production.material-requested` 를 발행한다 (outbox)
   ```json
   { "productionId": 101, "parts": [ { "partId": 1001, "qty": 2 }, { "partId": 1011, "qty": 8 } ] }
   ```
   - 차량 1대분이므로 부품 수량 = BOM.qty_per_unit
6. 생산 건마다 `production.status-changed`(PLANNED) 를 보낸다. order 는 이것으로 차량(생산 건)을 알게 된다 (SAGA 성공 흐름 2번)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 이벤트를 두 번 받음 | 두 번째는 건너뛴다 (ProcessedEventGuard). 생산 건과 이벤트가 한 번만 생긴다 |
| 한 이벤트에 같은 제품이 여러 줄 | 수량을 합친 만큼 차량을 만든다 (order 는 같은 제품 중복을 400 으로 막지만, 받는 쪽에서도 버리지 않는다) |
| 같은 주문·제품의 생산 건이 이미 있음 (`uk_production_order_product_seq`) | 그 제품은 새로 만들지 않는다. eventId 가 다른 재발행이어도 차량은 수량만큼만 있다 |
| 수량이 1 이상이 아님 | 처리 중 오류 (`INVALID_ORDER_QTY`). 재시도 후 DLQ. order 가 1 이상만 받으므로 정상 흐름에서는 생기지 않는다 |
| 처리 중 오류 (DB 등) | 트랜잭션 전체 롤백 (처리 기록 포함). 1s, 2s, 4s 간격으로 최대 3번 다시 처리한다. 그래도 실패하면 `EVENT_DEAD_LETTERED queue=… eventId=… reason=…` ERROR 로그를 남기고 `production.order-created.dlq` 에 보관한다 (dead letter exchange `factoryflow.events.dlx`) |
| JSON 이 깨진 메시지 | 처리 중 오류와 같다. 4번 시도한 뒤 DLQ 에 보관한다 (변환 실패도 재시도 대상) |
| 제품이 없거나 BOM 이 없음 (주문 이후 삭제) | 그 제품만 건너뛰고 ERROR 로그 `SAGA_SKIPPED orderId=… productId=… reason=…` 를 남긴다. 나머지 제품은 생산 건을 만든다 (건너뛴 주문 품목은 RECEIVED 에 머문다) |

- 주문 생성 때 제품과 BOM 을 이미 확인하므로(SAGA 0. 입구 검증) 정상 흐름에서는 생기지 않는다. 주문과 수신 사이에 삭제된 경우만 해당한다
- 전체를 실패로 두지 않는 이유: 다시 처리해도 계속 실패해, 재시도를 다 쓰면 메시지가 DLQ 로 가고 같은 주문의 다른 제품까지 생산 건이 생기지 않는다
- 리스너(`event/listener/OrderEventListener`)는 `ProcessedEventGuard` 로 중복을 거르고 `ProductionService` 를 부른다 (/add-event 수신 규칙). Repository 는 직접 쓰지 않고, 생산 건 저장과 이벤트 발행은 `ProductionService` 가 한다

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| order.created | order → production | orderId, products[{productId, qty}] |
| production.material-requested | production → inventory | productionId, parts[{partId, qty}] |
| production.status-changed | production → order | orderId, productionId, productId, status |

## 완료 조건 (테스트로 확인할 것)
- [ ] 제품 A 2대, B 1대 주문을 받으면 생산 건 3개(PLANNED, A 는 seq 1·2)와 `production.material-requested` 3건, `production.status-changed`(PLANNED) 3건이 outbox 에 저장된다
- [ ] material-requested 의 부품 수량은 차량 1대분(BOM.qty_per_unit)이고, 삭제된 BOM 은 빠진다
- [ ] 같은 이벤트를 두 번 받으면 생산 건과 outbox 가 한 번만 생긴다
- [ ] 같은 주문·제품으로 다른 eventId 가 와도 차량은 수량만큼만 있다
- [ ] 한 이벤트에 같은 제품이 두 줄이면 수량을 합친 만큼 차량과 부품 요청이 생긴다
- [ ] RabbitMQ 로 실제 `order.created` 를 보내면 리스너가 받아 생산 건이 생긴다 (Testcontainers)
- [ ] 처리가 계속 실패하는 메시지는 4번 시도한 뒤 EVENT_DEAD_LETTERED 로그를 남기고 DLQ 에 보관되며, 아무것도 저장되지 않는다
- [ ] JSON 이 깨진 메시지도 DLQ 에 원래 본문 그대로 보관된다
- [ ] 제품 2종 중 하나의 BOM 이 사라졌으면 나머지 하나만 생산 건과 material-requested 가 생긴다
