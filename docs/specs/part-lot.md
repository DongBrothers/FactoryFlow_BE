# 부품 LOT ↔ VIN (입고 때 LOT 부여 → 생산 완료 때 소모 LOT 기록 → VIN 으로 조회)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 9번의 "소모한 부품 LOT ↔ VIN 연결"이다 (#76).
입고 한 번에 LOT 하나를 붙이고, 차가 완성될 때 그 차에 들어간 부품이 어느 LOT 에서 나왔는지 남긴다.
VIN 으로 그 차에 쓰인 부품 LOT 을, LOT 으로 그 LOT 이 들어간 차를 조회할 수 있다 (추적성: 불량 부품이 어느 차에 들어갔는지, 리콜 대상 찾기).

- LOT 은 inventory 가 붙인다. 입고(`purchase.received`)를 받아 재고를 늘리는 곳이 inventory 라, 이벤트 계약을 바꾸지 않아도 된다
- 어느 LOT 을 썼는지는 **소모할 때(생산 완료) 먼저 들어온 LOT 부터(FIFO)** 정한다. 예약할 때 LOT 을 정하면 취소·재배정 때 LOT 수량도 되돌려야 해서 복잡해진다
- LOT 을 붙이기 전부터 있던 재고(지금 재고, dev 시드)는 "LOT 없음"으로 남는다. 가장 오래된 재고라 FIFO 에서 먼저 쓴다
- LOT 별 재고 조회, 공급업체 LOT 번호 입력은 넣지 않는다

## 흐름

### 1. 입고 → LOT 부여 (inventory ← purchase.received, 지금 처리에 더함)
입고 처리(docs/specs/purchase-receiving.md "3. 입고")와 같은 트랜잭션에서
- `part_lot { partId, lotNo, purchaseId, receivedQty = qty, remainingQty = qty, receivedAt = 지금 }` 한 줄
- LOT 번호: `LOT-{입고일 yyMMdd}-{purchaseId 6자리}` (예: `LOT-261017-000012`). 구매 건 하나 = 부품 하나 = 입고 한 번이라 겹치지 않는다
- 같은 구매 건 입고를 다시 받으면 지금처럼 입고 이력으로 건너뛴다 (LOT 도 한 번만)

### 2. 생산 완료 → 소모 LOT 기록 (inventory ← production.completed, 지금 처리에 더함)
예약 줄을 CONSUMED 로 바꾸는 지금 처리(docs/specs/production-completion.md)와 같은 트랜잭션에서, 예약 줄(부품, qty)마다
1. 창고 실물 = available + reserved (소모 전), LOT 이 있는 수량 = 그 부품 LOT 들의 remainingQty 합, **LOT 없는 수량 = 실물 − LOT 있는 수량**
2. LOT 없는 수량에서 먼저 뺀다 → `part_lot_usage { vin, productionId, partId, lotId = NULL, qty }`
3. 모자라면 remainingQty > 0 인 LOT 을 입고 순서(id)로 빼 나간다. LOT 마다 remainingQty −, `part_lot_usage { …, lotId }` 한 줄 (한 부품이 LOT 두 개에 걸칠 수 있다)
4. 끝나면 그 부품의 LOT remainingQty 합 ≤ available + reserved (소모 후) 가 유지된다

동시성: 입고와 소모는 둘 다 그 부품 행을 바꾸므로 지금의 부품 version 이 LOT 변경도 함께 막는다 (LOT 에 따로 version 을 두지 않는다).
예약 해제(취소)는 실물이 그대로라 LOT 을 바꾸지 않는다.

### 3. VIN 으로 부품 LOT 조회 (inventory) `GET /inventory/finished-vehicles/{vin}/part-lots`
gateway 경로 `/api/inventory/finished-vehicles/{vin}/part-lots`
```json
[ { "partCode": "ENG-G16", "partName": "가솔린 엔진 1.6", "lotNo": "LOT-261017-000012", "qty": 1, "receivedAt": "2026-10-17T10:00:00" },
  { "partCode": "TIRE-16", "partName": "타이어 16인치", "lotNo": null, "qty": 4, "receivedAt": null } ]
```
- 부품 코드 순, 같은 부품은 LOT 입고 순 (LOT 없음이 먼저). `lotNo = null` 은 LOT 도입 전 재고
- vin 형식 400 (출고 API 와 같은 규칙), 완성차 없음 404 `FINISHED_VEHICLE_NOT_FOUND`
- 내부 id(부품 id, LOT id, 생산 건 id)는 내보내지 않는다

### 4. LOT 으로 차 조회 (inventory) `GET /inventory/part-lots/{lotNo}/vehicles`
gateway 경로 `/api/inventory/part-lots/{lotNo}/vehicles`. 그 LOT 의 부품이 들어간 차 (리콜 대상 찾기)
```json
[ { "vin": "KMH000017TU000012", "productId": 1, "status": "SHIPPED", "partCode": "ENG-G16", "qty": 1 } ]
```
- 사용 기록 순서(먼저 완성된 차 먼저). status 는 완성차 상태 (IN_STOCK = 창고에 있음, SHIPPED = 출고됨)
- lotNo 형식 400 (`LOT-` + 숫자 6자리 + `-` + 숫자 6자리), 없는 LOT 404 `PART_LOT_NOT_FOUND`, 아직 쓰인 차가 없으면 빈 목록
- 내부 id 는 내보내지 않는다

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 입고를 두 번 받음 | 입고 이력으로 건너뛰므로 LOT 도 한 번 |
| 같은 completed 를 두 번 받음 | 지금처럼 완성차가 있으면 건너뛰므로 사용 기록도 한 번 |
| LOT 없는 재고와 LOT 이 모두 모자람 | 생기지 않는다 (예약된 수량은 실물 안에 있다). 생기면 처리 오류 → 재시도 → DLQ |
| 입고와 소모가 동시에 같은 부품 | 부품 version 으로 한쪽만 반영, 늦은 쪽 재시도 (지금 규칙) |
| 완성차는 있는데 사용 기록이 없음 (이 기능 전에 완성된 차) | 빈 목록 200 |

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| inventory (V5) | `part_lot`: id, part_id, lot_no (UNIQUE), purchase_id (UNIQUE), received_qty (> 0), remaining_qty (≥ 0, ≤ received_qty), received_at, created_at, updated_at, is_deleted. 인덱스 (part_id, id) |
| inventory (V5) | `part_lot_usage`: id, vin, production_id, part_id, lot_id (NULL = LOT 없음), qty (> 0), created_at. 인덱스 (vin) |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| purchase.received | purchase → inventory (`inventory.purchase-received`) | purchaseId, partId, qty |
| production.completed | production → inventory (`inventory.production-completed`) | orderId, productionId, productId, vin |
바뀌는 이벤트 없음.

## 완료 조건 (테스트로 확인할 것)
inventory
- [ ] 입고 → LOT 한 줄 (번호 형식, receivedQty = remainingQty = qty), 같은 입고 두 번 → LOT 한 번
- [ ] 생산 완료 → LOT 없는 수량부터, 그다음 LOT 입고 순으로 빼고 사용 기록. 한 부품이 LOT 두 개에 걸치는 경우
- [ ] LOT remainingQty 합 ≤ available + reserved 유지, 같은 completed 두 번 → 사용 기록 한 번
- [ ] 취소(예약 해제)는 LOT 을 바꾸지 않음
- [ ] VIN 조회: 부품·LOT·수량·입고 시각, LOT 없음은 null, 내부 id 없음, 400·404, 사용 기록 없으면 빈 목록
- [ ] LOT 조회: 그 LOT 이 들어간 차(VIN, 제품, 완성차 상태, 수량), 내부 id 없음, 400·404, 쓰인 차가 없으면 빈 목록
- [ ] V5 마이그레이션 (UNIQUE, CHECK)
