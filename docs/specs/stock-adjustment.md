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
     - available 이 |qty| 이상이어야 한다. 생산에 잡힌 수량(reserved)은 조정으로 줄이지 않는다 (줄이려면 그 차를 취소)
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

### LOT 번호
- 조정 LOT `LOT-ADJ-000012` 를 LOT 조회 API(`GET /inventory/part-lots/{lotNo}/vehicles`)의 번호 형식에 더한다
- LOT 별 재고(`GET /inventory/parts/{partId}/lots`)와 차량별 LOT 조회에는 다른 LOT 과 똑같이 나온다

## 규칙 / 예외
| 상황 | 응답 | 저장 / 이벤트 |
|---|---|---|
| qty 없음, 0 (커스텀 제약 `@NotZero`) | 400 | 없음 |
| reason 없음·공백·255자 초과 | 400 | 없음 |
| X-User-Id 없음·공백·36자 초과 | 400 | 없음 |
| partId 가 양수가 아님 | 400 | 없음 |
| 부품 없음 (지운 것 포함) | 404 `PART_NOT_FOUND` | 없음 |
| 줄이는 수량이 available 보다 많음 | 409 `INSUFFICIENT_AVAILABLE` | 없음 |
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
| inventory.reserved | inventory → production | 늘린 재고로 기다리던 차의 줄이 다 찼을 때 (기존 이벤트, payload 그대로) |

## 완료 조건 (테스트로 확인할 것)
- [ ] 늘리기: available +, ADJUST_IN 이력, 조정 LOT(LOT-ADJ-…, 구매 건 없음), 응답 lotNo
- [ ] 늘리기가 기다리던 줄을 먼저 온 순서로 채우고, 다 찬 차만 inventory.reserved (앞 줄이 안 들어가면 멈춤)
- [ ] 줄이기: available −, ADJUST_OUT 이력, LOT 을 입고 순으로 뺌 (두 LOT 에 걸치는 경우 포함), 응답 lotNo null
- [ ] 줄이는 수량 > available: 409, reserved·LOT 그대로
- [ ] qty 0·없음, reason 없음, X-User-Id 없음 400. 없는·지운 부품 404
- [ ] 조정 LOT 번호로 LOT 조회 API 가 400 이 아님
- [ ] gateway: PURCHASE 만, 나머지 403
- [ ] MigrationTest: V8 stock_adjustment, qty 0 CHECK
