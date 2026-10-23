# 부품 계보(VIN ↔ LOT)를 mes 로

## 목적
경계를 한 줄로 맞춘다: **ERP(inventory)는 재고 수량, MES(mes)는 제품 계보.**
지금은 의장에서 찍은 LOT 을 mes 가 남기고(#174), 차에 들어간 LOT 의 최종 기록(`part_lot_usage`)과 리콜 조회는 inventory 에 있다. 같은 사실("이 VIN 에 이 LOT")이 두 서비스에 나뉘어 있다.
"이 VIN 에 이 LOT 이 들어갔다" 는 의장 현장에서 생기는 사실이므로 생긴 곳(mes)이 하나뿐인 주인이 된다 (docs/planning/erp-mes-boundary.md 회색 지대 표를 바꾼다).

- 일반 경우만 본다: 작업자가 맞게 찍었다. 잘못 찍은 LOT, 스캔과 재고 차감 사이 시간 차는 다루지 않는다 (알려진 빈틈)
- 새 이벤트·동기 호출 없음. 필요한 정보는 이미 완성 이벤트에 있다 (`scannedLots`, docs/specs/part-lot-scan.md)

## 흐름

### 1. 계보 = mes 의 찍은 LOT (지금 그대로)
- 의장 양품 완료 때 찍은 LOT 을 `work_order_part_scan` 에 남긴다 (#174)
- VIN 은 검사 양품 때 작업지시에 생긴다. 계보 = `work_order_part_scan` + `work_order.vin`
- 안 찍은 부품은 계보가 없다 (현업도 일반 부품은 VIN 계보를 남기지 않는다)

### 2. 계보 조회 (mes, READ)
`GET /mes/vehicles/{vin}/part-lots` (gateway `/api/mes/vehicles/{vin}/part-lots`)
```json
[ { "partId": 1001, "lotNo": "LOT-261017-000012", "scannedBy": "…", "scannedAt": "2026-10-23T22:09:09" } ]
```
- 부품 id 순. VIN 형식이 아니면 400, 그 VIN 의 작업지시가 없으면 404 `WORK_ORDER_NOT_FOUND`, 찍은 것이 없으면 빈 목록

`GET /mes/part-lots/{lotNo}/vehicles` (리콜 대상)
```json
[ { "workOrderId": 12, "vin": "KMH000013TU000012", "productId": 1, "partId": 1001, "scannedAt": "…" } ]
```
- 찍은 시각 순. **아직 완성되지 않은 차도 나온다**(vin 이 null) — 의장을 지난 차는 리콜 대상이다
- lotNo 형식(`LOT-` 로 시작, 공백 없음, 32자 이하)이 아니면 400. mes 는 LOT 이 실제로 있는지 모르므로 없는 LOT 은 빈 목록
- 부품 이름·LOT 입고 시각·출고 여부는 inventory 데이터라 넣지 않는다. 화면은 부품 목록(`GET /api/inventory/parts`), 완성차 조회(`GET /api/inventory/finished-vehicles`)로 붙인다 (MSA: 조회는 화면에서 조합)

### 3. inventory 는 재고만
- 완성 때 재고 차감은 그대로: 찍은 LOT 이 그 부품이고 남은 수량이 되면 그 LOT, 아니면 입고 순 (#174)
- VIN 별 사용 기록(`part_lot_usage`)을 남기지 않는다. LOT 남은 수량과 재고 이력(stock_history CONSUME, 생산 건 기준)이 재고 쪽 근거다
- 없앤다: `GET /inventory/finished-vehicles/{vin}/part-lots`, `GET /inventory/part-lots/{lotNo}/vehicles`, `PartLotUsage`·`PartLotUsageSource`·`PartLotUsageRepository`, 응답 `VehiclePartLotResponse`·`LotVehicleResponse`
- 남긴다: LOT 별 재고 `GET /inventory/parts/{partId}/lots` (재고 수량이라 inventory 것)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 찍지 않은 부품 | 계보 없음. 재고는 입고 순으로 뺀다 |
| 잘못 찍은 LOT (없음·다른 부품·수량 부족) | 일반 경우가 아님. mes 계보에는 찍은 대로 남고 inventory 는 입고 순 + `SCANNED_LOT_REJECTED` WARN (알려진 빈틈) |
| 의장 뒤 취소 | 첫 공정 시작 뒤에는 취소가 없으므로 해당 없음 |
| 이 변경 전 inventory 의 VIN 별 기록 | 옮기지 않고 버린다 (dev 데이터) |

## 테이블
| 서비스 | 변경 |
|---|---|
| mes (V4) | `work_order_part_scan` INDEX(lot_no) — LOT 으로 차 찾기 |
| inventory (V11) | `part_lot_usage` 삭제 (DROP TABLE) — one-way-door |

## 관련 이벤트 (events.md 참조)
없음 (mes.vehicle-completed·production.completed 의 scannedLots 를 그대로 쓴다)

## 완료 조건 (테스트로 확인할 것)
- [ ] mes: VIN 으로 찍은 LOT 조회 (부품 id 순, 찍은 것 없으면 빈 목록), VIN 형식 400, 없는 VIN 404
- [ ] mes: LOT 으로 차 조회 (찍은 시각 순, 완성 전 차는 vin null), lotNo 형식 400, 없는 LOT 빈 목록
- [ ] mes: Swagger 문서화
- [ ] inventory: 완성 때 찍은 LOT·입고 순으로 남은 수량만 줄고 VIN 별 기록은 없음
- [ ] inventory: LOT 조회 API 두 개가 없음 (404), LOT 별 재고는 그대로
- [ ] MigrationTest: mes V4 인덱스, inventory V11 part_lot_usage 없음
