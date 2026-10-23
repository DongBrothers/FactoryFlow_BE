# 의장 공정 LOT 스캔 (부품 LOT ↔ VIN 을 현장에서 기록)

## 목적
지금 차에 들어간 부품 LOT(`part_lot_usage`)은 생산 완료 때 inventory 가 **먼저 들어온 LOT 부터 배정한 추정값**이다 (docs/specs/part-lot.md). 실제로 어느 LOT 을 달았는지는 모른다.
현업은 의장 라인에서 작업자가 중요 부품(엔진, ECU 등)의 LOT 바코드를 찍고, MES 가 "이 차에 이 LOT" 을 기록한다(제품 계보). ERP 는 그 LOT 으로 재고를 뺀다. 리콜 때 이 기록으로 대상 차를 찾는다 (#170 3번, docs/planning/erp-mes-boundary.md "회색 지대").

- 스캔은 **선택**이다. 찍은 부품만 그 LOT 으로, 안 찍은 부품은 지금처럼 입고 순으로 뺀다
- 부품 하나에 LOT 하나만 찍는다 (한 차에 같은 부품이 여러 개여도 같은 LOT 에서 나왔다고 본다)
- 서비스 사이 동기 호출은 없다. 스캔 기록은 이미 있는 완성 이벤트에 실려 간다 (재고 조정 거둬들이기 PR #172 를 접은 이유: 동기 호출 + 보상)

## 흐름

### 1. 의장 공정 완료 때 스캔 (mes) `POST /mes/work-orders/{workOrderId}/steps/ASSEMBLY/complete`
지금 요청에 `scannedLots` 를 더한다 (PRODUCTION, gateway `/api/mes/...`)
```json
{ "result": "GOOD",
  "scannedLots": [ { "partId": 1001, "lotNo": "LOT-261017-000012" },
                   { "partId": 1018, "lotNo": "LOT-INIT-1018" } ] }
```
- 형식 (Bean Validation, 400): 목록은 없어도 되고 최대 50개. partId 1 이상, lotNo `LOT-` 로 시작하는 32자 이하(공백 없음). 같은 partId 두 번은 400 (커스텀 제약)
- `ASSEMBLY` 가 아니거나 `result` 가 `GOOD` 이 아닌데 scannedLots 가 비어 있지 않으면 400 `SCAN_NOT_ALLOWED` (불량으로 끝낸 공정은 재작업 때 다시 찍는다)
- 의장 양품이면 작업지시의 스캔 기록을 **이번 것으로 바꾼다** (재작업으로 두 번 끝나면 마지막 것만 남는다). 같은 트랜잭션
- mes 는 LOT 이 실제로 있는지, 그 부품이 차의 BOM 에 있는지 모른다. 확인은 재고를 빼는 inventory 가 한다 (아래 3)

### 2. 차 완성 → 이벤트에 스캔 기록을 싣는다
- mes: 검사 양품으로 VIN 을 발행할 때 `mes.vehicle-completed` 에 그 작업지시의 스캔 기록을 싣는다
  `{ productionId, workOrderId, vin, scannedLots: [ { partId, lotNo } ] }` (없으면 빈 목록)
- production: 받은 `scannedLots` 를 `production.completed` 에 그대로 싣는다
  `{ orderId, productionId, productId, vin, scannedLots: [ { partId, lotNo } ] }`
- order 는 production.completed 의 scannedLots 를 쓰지 않는다 (모르는 필드는 무시)

### 3. 재고 차감 (inventory ← production.completed, 지금 처리에서 LOT 고르기만 바뀜)
예약 줄(부품, qty)마다
- 그 부품의 스캔 LOT 이 있고, **그 부품의 LOT 이며 남은 수량 ≥ qty** 이면 그 LOT 에서 qty 를 다 뺀다. 사용 기록 `source = SCANNED`
- 스캔이 없거나 위 조건이 안 맞으면 지금처럼 입고 순으로 뺀다. 사용 기록 `source = FIFO`
  - 스캔했는데 못 쓴 경우(없는 LOT, 다른 부품의 LOT, 남은 수량 부족)는 `SCANNED_LOT_REJECTED vin=… partId=… lotNo=… reason=…` WARN 로그. 차 완성은 막지 않는다 (현업도 이미 만든 차를 잘못 찍었다고 멈추지 않는다)
- 차의 예약 줄에 없는 부품을 찍었으면 무시하고 같은 WARN (reason=NOT_IN_VEHICLE)
- 나머지(예약 CONSUMED, 완성차 재고, 보관 자리)는 지금 그대로

### 4. 조회에 스캔 여부를 보인다 (inventory)
- `GET /inventory/finished-vehicles/{vin}/part-lots` 항목에 `source` (`SCANNED`·`FIFO`)
- `GET /inventory/part-lots/{lotNo}/vehicles` 항목에 `source`
- 리콜 대상을 찾을 때 SCANNED 는 확실, FIFO 는 추정으로 구분해 볼 수 있다

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| scannedLots 를 안 보냄·빈 목록 | 지금과 같다 (모두 FIFO) |
| ASSEMBLY 아님, 또는 DEFECT 인데 scannedLots 있음 | 400 `SCAN_NOT_ALLOWED`, 공정 그대로 |
| 같은 partId 두 번, lotNo 형식 틀림, 51개 이상 | 400 |
| 의장 재작업 (DEFECT → 다시 GOOD) | 마지막 양품 때 찍은 것만 남는다 |
| 스캔 LOT 이 없음·다른 부품·남은 수량 부족·차에 없는 부품 | 그 부품만 FIFO + WARN, 차 완성은 계속 |
| 이 변경 전 이벤트(scannedLots 없음) | 빈 목록으로 본다 |
| 같은 production.completed 를 두 번 받음 | 지금처럼 한 번만 반영 |

## 테이블
| 서비스 | 변경 |
|---|---|
| mes (V3) | `work_order_part_scan`: id, work_order_id, part_id, lot_no VARCHAR(32), scanned_by VARCHAR(36), scanned_at DATETIME(6). UNIQUE(work_order_id, part_id) |
| inventory (V10) | `part_lot_usage.source` VARCHAR(10) NOT NULL DEFAULT 'FIFO' (지금까지의 기록은 FIFO) |

## 관련 이벤트 (events.md 참조, 사람만 수정)
| 이벤트 | 바뀌는 것 |
|---|---|
| mes.vehicle-completed | payload 에 `scannedLots[{partId, lotNo}]` 추가 (없으면 빈 목록) |
| production.completed | payload 에 `scannedLots[{partId, lotNo}]` 추가 (받은 것 그대로) |

- 필드 추가만이라 이전 수신자는 무시하고, 이전 발신 메시지는 빈 목록으로 읽는다
- events.md 와 common-event 는 사람이 적용한다 (아래 "사람이 할 일")
- 이벤트 계약 변경이라 one-way-door. 되돌릴 때: 수신 쪽을 먼저 되돌려도 필드를 무시하므로 안전하고, V3·V10 은 테이블·컬럼 추가만이라 남겨 둬도 된다

## 사람이 할 일
- docs/specs/events.md 의 두 줄 payload 에 `scannedLots[{partId, lotNo}]` 추가
- common/common-event 에 바꿀 것이 있으면 적용 (payload 는 각 서비스의 publisher/listener payload record 라 common-event 변경은 없을 것으로 본다)

## 완료 조건 (테스트로 확인할 것)
- [ ] mes: 의장 양품 완료 + scannedLots → 스캔 기록 저장 (작업자, 시각), 재작업 뒤 다시 양품이면 마지막 것만
- [ ] mes: ASSEMBLY 아님·DEFECT 인데 scannedLots → 400 SCAN_NOT_ALLOWED, 공정·기록 그대로
- [ ] mes: 같은 partId 두 번·lotNo 형식·51개 → 400
- [ ] mes: 검사 양품 → mes.vehicle-completed 에 scannedLots (없으면 빈 목록)
- [ ] production: mes.vehicle-completed 의 scannedLots 를 production.completed 에 그대로, 없는 옛 메시지는 빈 목록
- [ ] order: scannedLots 가 실린 production.completed 를 지금처럼 처리 (모르는 필드 무시)
- [ ] inventory: 스캔 LOT 에서 그 부품 qty 를 빼고 SCANNED, 안 찍은 부품은 입고 순 FIFO
- [ ] inventory: 없는 LOT·다른 부품 LOT·남은 수량 부족·차에 없는 부품 → 그 부품 FIFO + WARN, 완성은 계속
- [ ] inventory: 차량별 LOT·LOT 별 차량 조회에 source
- [ ] MigrationTest: mes V3 work_order_part_scan, inventory V10 source 기본값 FIFO
- [ ] Swagger: 의장 완료 요청의 scannedLots, 조회 응답의 source 문서화
