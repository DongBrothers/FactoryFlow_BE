# 부품 예약 (production.material-requested 수신)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 3번이다.
inventory 가 `production.material-requested` 를 받아 생산 건에 필요한 부품을 예약하고, 결과를 production 에 알린다.
재고가 모자라면 기다리는 줄(WAITING)을 만든다. (처음에는 새로 늘어난 부족분을 `inventory.shortage` 로 알렸지만 #114 에서 없앴다. 구매 담당이 부족 부품 조회로 본다, docs/specs/purchase-manual.md)

## 흐름
1. production 이 `production.material-requested` 를 발행한다: `{ productionId, parts: [{ partId, qty }] }`
2. inventory 가 큐 `inventory.production-material-requested`(라우팅 키 `production.material-requested`)로 받는다
3. `ProcessedEventGuard` 로 같은 이벤트를 한 번만 처리한다. 아래 4~6 은 처리 기록과 같은 트랜잭션이다
4. 부품마다 예약 줄(`part_reservation`)을 만든다. 부품은 partId 순서로 처리한다
   - 같은 부품에 WAITING 줄이 없고 `available >= qty` 이면 **RESERVED**: available −qty, reserved +qty, stock_history RESERVE
   - 그 외에는 **WAITING**: 재고는 그대로. 앞에 기다리는 줄이 있으면 재고가 있어도 뒤에 선다 (먼저 온 순서 보장)
5. (#114 에서 없앰) ~~WAITING 줄을 만들면서 부족이 늘었으면 부품마다 `inventory.shortage` 를 발행한다~~
   - 이전 부족 = max(0, 그 부품의 WAITING qty 합 − available), 이후 부족 = max(0, WAITING qty 합 + 이번 qty − available)
   - shortQty = 이후 − 이전. 0 이면 보내지 않는다 (예: available 3, 대기 2, 새 요청 2 → shortQty 1)
   - payload: `{ partId, partCode, partName, shortQty }`
6. 생산 건의 결과를 하나 발행한다
   - 모든 줄이 RESERVED → `inventory.reserved { productionId }`
   - 하나라도 WAITING → `inventory.reservation-pending { productionId, shortages: [{ partId, shortQty }] }`
     (shortages = 이 생산 건의 WAITING 줄. shortQty 는 그 줄의 qty)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 이벤트를 두 번 받음 | 두 번째는 건너뛴다 (ProcessedEventGuard) |
| 이 생산 건의 예약 줄이 이미 있음 (`uk_part_reservation_production_part`) | 새로 만들지 않고 결과 이벤트도 다시 보내지 않는다 |
| 한 이벤트에 같은 부품이 여러 줄 | 수량을 합쳐 예약 줄 하나로 만든다 |
| 동시에 같은 부품을 예약 | 요청한 부품을 `OPTIMISTIC_FORCE_INCREMENT` 로 읽어, WAITING 만 만들어도 커밋 때 `part.version` 을 올린다. 늦게 커밋하는 쪽이 충돌 → 롤백 → 재시도 때 앞 요청의 WAITING 줄을 보고 뒤에 선다 |
| 없는 부품 (partId 가 part 에 없거나 삭제됨) | 처리 중 오류로 본다 (`PART_NOT_FOUND`). 재시도 후 DLQ 로 가고, 생산 건은 PLANNED 에 머문다 |
| 처리 중 오류 | production 과 같다: 트랜잭션 롤백, 1s·2s·4s 로 3번 재시도, 그래도 실패하면 `EVENT_DEAD_LETTERED` 로그 후 `inventory.production-material-requested.dlq` |

- `part.reserved` 는 항상 그 부품의 RESERVED 예약 qty 합과 같다 (같은 트랜잭션에서 갱신)
- traceId: 받은 이벤트의 traceId 를 발행하는 이벤트에 그대로 쓴다 (SAGA "추적")
- 리스너(`event/listener/ProductionEventListener`)는 `ProcessedEventGuard` 로 중복을 거르고 `PartReservationService` 를 부른다

- 없는 부품을 오류로 보는 이유: BOM 등록 때 부품 존재를 확인하므로(SAGA 0. 입구 검증) 정상 흐름에서는 생기지 않고, 시드나 DB 를 잘못 고쳤을 때만 생긴다.
  건너뛰면 필요한 부품이 빠진 채 생산이 READY 가 될 수 있으므로, DLQ 에 남겨 데이터를 고친 뒤 다시 처리한다

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| production.material-requested | production → inventory | productionId, parts[{partId, qty}] |
| inventory.reserved | inventory → production | productionId |
| inventory.reservation-pending | inventory → production | productionId, shortages[{partId, shortQty}] |
| ~~inventory.shortage~~ | (#114 에서 없앰) | |

SAGA 흐름 중 이 작업은 inventory 의 예약만 다룬다. production 의 reserved / reservation-pending 수신(SAGA 4번)과 purchase 의 shortage 수신은 각각 다음 작업이다.

## 함께 한 작업
- production traceId: production 리스너가 받은 `order.created` 의 traceId 를 이어 쓰지 않아 `production.material-requested` 가 새 traceId 로 발행되던 문제를 고친다 (SAGA "추적" 규칙). inventory 리스너도 같은 방식으로 받은 traceId 를 이어 쓴다
- 메서드 길이 규칙: 서비스 코드 메서드는 30줄 이하 (ArchUnit `METHOD_LENGTH`). 이 작업의 `reserve` 와 이미 있던 `ProductionService.planAll` 이 걸려 단계별 private 메서드로 나눈다 (동작은 그대로)

## 완료 조건 (테스트로 확인할 것)
- [ ] 재고가 충분하면 모든 줄 RESERVED, available·reserved 변경, stock_history RESERVE, `inventory.reserved` 1건
- [ ] 한 부품이라도 부족하면 그 줄은 WAITING, 재고 변화 없음, `inventory.reservation-pending`(shortages 포함)과 `inventory.shortage` 발행
- [ ] 같은 부품에 WAITING 줄이 있으면 재고가 있어도 새 줄은 WAITING 으로 뒤에 선다
- [ ] shortQty 는 대기 수량을 포함한 증가분만 계산된다 (available 3, 대기 2, 새 요청 2 → 1). 0 이면 shortage 를 보내지 않는다
- [ ] part.reserved 는 RESERVED 예약 qty 합과 같다
- [ ] 같은 이벤트를 두 번 받아도 재고와 예약 줄이 한 번만 바뀐다
- [ ] 같은 생산 건으로 다른 eventId 가 와도 예약 줄과 결과 이벤트는 한 번만 생긴다
- [ ] 발행한 이벤트의 traceId 는 받은 이벤트의 traceId 와 같다
- [ ] WAITING 이 된 부품도 version 이 올라 동시 요청이 충돌한다
- [ ] RabbitMQ 로 실제 `production.material-requested` 를 보내면 처리된다 (Testcontainers)
- [ ] 없는 부품이 섞인 요청은 아무것도 저장하지 않고 재시도 후 DLQ 로 간다
