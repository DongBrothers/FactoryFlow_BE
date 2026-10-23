# 재고 조정

## 목적
창고 실물과 시스템 수량이 어긋나면(파손, 분실, 세다가 더 나옴) 지금은 고칠 방법이 없다. 구매·자재 담당이 부품의 바로 쓸 수 있는 수량(available)을 늘리거나 줄이고, 그 사유와 담당자를 남긴다.
실사 절차(실사 목록, 승인)는 하지 않는다. 한 번에 부품 하나를 조정한다.

## 흐름

### 재고 조정 `POST /inventory/parts/{partId}/adjustments` (PURCHASE)
gateway `/api/inventory/parts/{partId}/adjustments`, 헤더 `X-User-Id`(gateway 가 채우는 직원 public_id)
```json
{ "qty": -2, "reason": "운반 중 파손" }
```
- qty 는 늘릴 때 양수, 줄일 때 음수. 0 은 안 된다
- 한 트랜잭션에서 (부품은 입고·예약과 같은 잠금으로 읽는다: `findAllByIdInAndIsDeletedFalse`, OPTIMISTIC_FORCE_INCREMENT)
  1. `stock_adjustment` 1줄 (부품, qty, 사유, 담당자)
  2. 늘릴 때 (qty > 0)
     - part.available + qty
     - 재고 이력 `ADJUST_IN` (ref_type `ADJUSTMENT`, ref_id = 조정 id)
     - **조정 LOT** 1줄: `LOT-ADJ-{조정 id, 6자리가 안 되면 앞을 0 으로}`, 구매 건 없음, 받은 수량 = 남은 수량 = qty, 입고 시각 = 지금 ("모든 재고에 LOT 이 있다", docs/specs/part-lot.md)
     - 입고와 같은 규칙으로 그 부품을 기다리는 줄(WAITING)을 먼저 온 순서로 채우고, 다 찬 차는 `inventory.reserved` (`PartReservationService` 의 줄 채우기를 그대로 쓴다)
  3. 줄일 때 (qty < 0)
     - available 이 |qty| 이상이면 그대로 뺀다
     - 모자라면 아래 "찜한 부품 거둬들이기" (#170) 로 발행 전 차의 예약을 먼저 풀어 available 을 채운 뒤 뺀다
     - part.available − |qty|
     - 재고 이력 `ADJUST_OUT` (ref_type `ADJUSTMENT`, ref_id = 조정 id)
     - LOT 남은 수량을 입고 순(입고 시각, 같으면 id)으로 |qty| 만큼 뺀다. 생산 완료 때 LOT 을 쓰는 순서와 같다
- 응답 전에 저장(flush)해 version 을 확인한다. 다른 처리가 같은 부품을 먼저 바꿨으면 409 `PART_CONFLICT`
- `201` 과 조정 결과
  ```json
  { "adjustmentId": 12, "partId": 1012, "qty": -2, "reason": "운반 중 파손",
    "available": 4, "reserved": 6, "lotNo": null }
  ```
  - available, reserved 는 조정 뒤 부품 수량. lotNo 는 늘렸을 때 만든 조정 LOT 번호 (줄였으면 null)

### 찜한 부품 거둬들이기 (#170)
줄일 수량이 available 보다 많을 때, 모자란 만큼(부족분 = |qty| − available) 차가 잡아 둔(RESERVED) 그 부품을 거둬 파손·분실을 반영한다. 현업에서도 자재가 없어지면 아직 라인에 들어가지 않은 차의 자재 할당을 풀고, 그 차는 자재를 다시 기다린다.

- **대상**: 그 부품의 RESERVED 예약 중 **작업지시를 발행하지 않은** 생산 건. 가장 늦게 잡은 예약(예약 id 큰 것)부터, 거둔 qty 합이 부족분 이상이 될 때까지
  - 발행한 차는 이미 현장(mes)에 넘어가 부품을 뺄 수 없다
- **발행 여부는 production 이 판단한다** (inventory 는 발행을 모른다, docs/planning/erp-mes-boundary.md)
  1. inventory 가 후보(RESERVED 예약, 예약 id 내림차순, productionId·qty)를 고른다 (트랜잭션 밖, 읽기만)
  2. production 내부 API `POST /internal/productions/material-holds` 를 동기로 부른다 (client, 트랜잭션 밖)
     ```json
     { "partId": 1012, "neededQty": 1, "candidates": [ { "productionId": 9, "qty": 4 }, { "productionId": 7, "qty": 4 } ] }
     ```
     - production 은 한 트랜잭션에서 후보를 순서대로 보고, **READY 이고 발행 전**(released_at 없음)인 건만 WAITING_MATERIAL 로 바꾸며 qty 를 더한다. 합이 neededQty 이상이 되면 멈춘다
     - 합이 모자라면 아무것도 바꾸지 않고 409 `NOT_ENOUGH_UNRELEASED`
     - 바꾼 건마다 `production.status-changed` (order 는 READY·WAITING_MATERIAL 을 모두 RECEIVED 로 보여 주므로 고객 화면은 그대로다)
     - 응답 `200 { "heldProductionIds": [9] }`
     - WAITING_MATERIAL 로 바뀐 차는 발행할 수 없다 (발행은 READY 에서만). 그래서 거두는 사이에 그 차가 발행되는 경쟁은 production 의 같은 행 잠금(version)으로 한쪽만 이긴다
  3. inventory `StockAdjustmentWriter` 의 한 트랜잭션에서
     - 거둔 생산 건의 그 부품 예약 RESERVED → **WAITING** (예약 id 는 그대로라 대기 줄에서 원래 순서 자리에 선다), part.reserved − qty, part.available + qty, 재고 이력 `RELEASE` (ref_type `PRODUCTION`, ref_id = 거둔 생산 건. 생산 취소로 풀 때와 같은 종류)
     - 그 뒤 위의 "줄일 때" 를 그대로 한다 (available − |qty|, ADJUST_OUT, LOT 차감)
     - 남은 available 로 기다리는 줄을 채우지 않는다. 거둔 차가 줄의 앞에 서므로, 그 차가 다 들어가지 않으면 뒤 차도 멈춘다 (기존 규칙)
     - production 이 되돌렸지만 그 사이 예약이 RESERVED 가 아니게 된 생산 건(취소 등)은 거두지 않고 `inventory.reserved` 로 READY 로 돌려보낸다
  - 거둔 차의 **다른 부품** 예약은 RESERVED 그대로 둔다. 이 부품이 다시 채워지면 기존 줄 채우기가 `inventory.reserved` 를 보내 READY 로 돌아간다
- **3 이 실패하면** (부품 version 충돌 등): production 은 이미 WAITING_MATERIAL 이고 inventory 예약은 RESERVED 다. 거둔 생산 건마다 `inventory.reserved` 를 다시 발행해(별도 트랜잭션, Outbox) READY 로 되돌리고, 조정은 409 `PART_CONFLICT` 로 끝낸다 (production 은 WAITING_MATERIAL 에서 inventory.reserved 를 받아 READY 로 바꾼다, 기존 동작)
- 응답에 `"reclaimedProductionIds": [9]` 를 더한다 (거두지 않았으면 빈 목록)

### LOT 번호
- 조정 LOT `LOT-ADJ-000012` 를 LOT 조회 API(`GET /inventory/part-lots/{lotNo}/vehicles`)의 번호 형식에 더한다
- LOT 별 재고(`GET /inventory/parts/{partId}/lots`)와 차량별 LOT 조회에는 다른 LOT 과 똑같이 나온다

## 규칙 / 예외
| 상황 | 응답 | 저장 / 이벤트 |
|---|---|---|
| qty 없음, 0 (커스텀 제약 `@NotZero`), ±1,000,000 밖 (계산이 넘치지 않게, PR #130 리뷰) | 400 | 없음 |
| reason 없음·공백·255자 초과 | 400 | 없음 |
| X-User-Id 없음·공백·36자 초과 | 400 | 없음 |
| partId 가 양수가 아님 | 400 | 없음 |
| 부품 없음 (지운 것 포함) | 404 `PART_NOT_FOUND` | 없음 |
| 줄이는 수량이 available 보다 많고, 발행 전 차에서 거둬도 모자람 | 409 `INSUFFICIENT_AVAILABLE` | 없음 (production 도 바꾸지 않음) |
| production 호출 실패·시간 초과 | 503 `PRODUCTION_UNAVAILABLE` | 없음 |
| 거둔 뒤 inventory 저장 실패 | 409 `PART_CONFLICT` | 거둔 생산 건마다 `inventory.reserved` 재발행 (READY 로 되돌림) |
| 다른 처리가 같은 부품을 먼저 바꿈 | 409 `PART_CONFLICT` | 없음 (롤백) |
| LOT 남은 수량 합이 줄일 수량보다 적음 (실물과 LOT 이 어긋난 데이터) | 500 `PART_LOT_SHORTAGE` | 없음 (롤백) |
| PURCHASE 가 아님 | 403 (gateway) | — |

- 조정은 고치거나 지우지 않는다 (추가만). 잘못 조정했으면 반대 방향으로 한 번 더 조정한다
- 늘릴 때 기다리는 줄을 채우는 것은 입고와 같다: 앞 줄이 다 들어가지 않으면 거기서 멈춘다 (뒤 줄이 새치기하지 않음)
- 조정 이력 목록 API 는 넣지 않는다 (필요하면 stock_adjustment, stock_history 로 본다)

### 테이블 (inventory V8)
`stock_adjustment` (추가만)
| 컬럼 | 타입 | 뜻 |
|---|---|---|
| id | BIGINT AUTO_INCREMENT | 조정 id. 재고 이력 ref_id, 조정 LOT 번호에 쓴다 |
| part_id | BIGINT NOT NULL | 부품 (논리 FK) |
| qty | INT NOT NULL | 늘리면 양수, 줄이면 음수. CHECK(qty <> 0) |
| reason | VARCHAR(255) NOT NULL | 사유 |
| adjusted_by | VARCHAR(36) NOT NULL | 조정한 직원 public_id (X-User-Id) |
| created_at | DATETIME(6) NOT NULL | 조정 시각 |
- INDEX(part_id, created_at)
- `stock_history.type` 에 `ADJUST_IN`, `ADJUST_OUT`, `ref_type` 에 `ADJUSTMENT` 가 더해진다 (컬럼은 VARCHAR 라 스키마 변경 없음)

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | 언제 |
|---|---|---|
| inventory.reserved | inventory → production | 늘린 재고로 기다리던 차의 줄이 다 찼을 때. 거둔 뒤 저장이 실패해 되돌릴 때 (기존 이벤트, payload 그대로) |
| production.status-changed | production → order | 거둔 차가 READY → WAITING_MATERIAL (기존 이벤트, order 차량 상태는 RECEIVED 그대로) |

- 새 이벤트는 없다. production 내부 API 와 inventory 의 production client(`clients.production.url`, APP_DEV_YML_INVENTORY 시크릿에 추가 필요)가 새로 생긴다

## 완료 조건 (테스트로 확인할 것)
- [ ] 늘리기: available +, ADJUST_IN 이력, 조정 LOT(LOT-ADJ-…, 구매 건 없음), 응답 lotNo
- [ ] 늘리기가 기다리던 줄을 먼저 온 순서로 채우고, 다 찬 차만 inventory.reserved (앞 줄이 안 들어가면 멈춤)
- [ ] 줄이기: available −, ADJUST_OUT 이력, LOT 을 입고 순으로 뺌 (두 LOT 에 걸치는 경우 포함), 응답 lotNo null
- [ ] 줄이는 수량 > available: 발행 전 차를 늦게 잡은 순으로 거둬 그 예약을 WAITING 으로, reserved −·available +, RELEASE 이력(ref PRODUCTION), 그 뒤 ADJUST_OUT. 응답 reclaimedProductionIds (#170)
- [ ] 거둔 예약은 대기 줄에서 원래 순서(예약 id) 자리에 서고, 남은 available 로 뒤 줄을 채우지 않는다
- [ ] production material-holds: READY·발행 전만 WAITING_MATERIAL + status-changed, 발행한 건 건너뜀, 합이 모자라면 아무것도 안 바꾸고 409
- [ ] 거둬도 모자람: 409 INSUFFICIENT_AVAILABLE, production·inventory 모두 그대로
- [ ] production 호출 실패: 503 PRODUCTION_UNAVAILABLE, 재고 그대로
- [ ] 거둔 뒤 inventory 저장 실패: 거둔 생산 건마다 inventory.reserved 재발행 → production READY 로 돌아옴
- [ ] production 이 되돌렸지만 예약이 이미 RESERVED 가 아닌 생산 건: 거두지 않고 inventory.reserved
- [ ] qty 0·없음, reason 없음, X-User-Id 없음 400. 없는·지운 부품 404
- [ ] 조정 LOT 번호로 LOT 조회 API 가 400 이 아님
- [ ] gateway: PURCHASE 만, 나머지 403
- [ ] MigrationTest: V8 stock_adjustment, qty 0 CHECK
