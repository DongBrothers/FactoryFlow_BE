# 불량이면 폐기, 같은 주문으로 대체 차

## 목적
지금은 공정에서 불량이 나면 같은 공정을 다시 한다. 현업에서는 이미 찍거나 용접·도장한 차를 같은 로봇 공정에 다시 넣지 않는다.
**어느 공정이든 불량이면 그 차를 폐기하고, 같은 주문·같은 제품으로 차를 새로 만든다.** 새 차는 지금 흐름 그대로 부품을 새로 받아(예약·대기) 발행을 기다린다.

- 수리(리워크) 분기는 두지 않는다 (불량 = 폐기)
- 대체 차의 작업지시는 지금처럼 생산 담당이 발행한다 (자동 발행 없음)

## 흐름
```
mes: 공정 불량 → 작업지시 SCRAPPED, 설비 비움 → mes.vehicle-scrapped
  ⇒ production: 생산 건 SCRAPPED + status-changed(SCRAPPED) + production.scrapped
                + 같은 주문·제품의 다음 차 번호로 생산 건 PLANNED (+ material-requested, status-changed(PLANNED))
  ⇒ inventory (production.scrapped): 폐기된 차가 잡아 둔 부품을 폐기 소모 (재고·LOT 에서 빠짐)
  ⇒ order (status-changed): 폐기된 차량 SCRAPPED, 대체 차량 RECEIVED 한 줄
대체 생산 건은 지금 흐름 그대로: 예약 → READY → 생산 담당 발행 → 공정 처음부터
```

### 1. mes: 불량 → 폐기
- 공정 종료가 불량(DEFECT)이면 — 작업자 입력(`…/complete { "result": "DEFECT", "defectReason" }`)이든 설비 보고(`equipment.step-completed` DEFECT)든 — 실적(불량, 사유)을 남기고
  - 그 공정은 끝내고(재작업 없음), 설비를 비우고
  - 작업지시를 **SCRAPPED**(끝, VIN 없음)로 바꾸고 `mes.vehicle-scrapped { productionId, workOrderId, process, defectReason }`
- 폐기된 작업지시는 더 진행하지 않는다: 배정 후보·작업자 시작에서 빠지고, 늦게 온 설비 보고는 무시한다 (지금 끝난 작업지시와 같다)
- 의장에서 찍은 LOT(`work_order_part_scan`)은 그대로 남는다 (계보 조회에 폐기된 작업지시도 나온다, vin 없음)
- 작업지시 목록 status 에 SCRAPPED 를 더하지 않는다 (목록은 RELEASED, IN_PROGRESS 그대로). 상세 조회는 SCRAPPED 를 보여 준다

### 2. production: 폐기 + 대체 생산 건
`mes.vehicle-scrapped` 를 받으면 한 트랜잭션에서
- 생산 건 IN_PROGRESS(또는 시작 알림보다 먼저 왔으면 READY) → **SCRAPPED** (끝) + `production.status-changed(SCRAPPED)` + `production.scrapped { orderId, productionId }`
- 같은 주문·같은 제품, 차 번호 = 그 주문·제품의 가장 큰 seq + 1 로 생산 건 PLANNED 를 만들고 지금 주문 때와 같이 `production.material-requested`(지금 BOM 으로 1대분), `production.status-changed(PLANNED)`
  - 제품이나 BOM 이 지금 없으면 대체 생산 건을 CANCELLED 로 만들고 status-changed(CANCELLED) (주문 뒤 제품·BOM 이 사라진 경우와 같은 처리, `SAGA_REJECTED` 로그)
- 이미 SCRAPPED·CANCELLED·COMPLETED 면 무시 (중복·늦은 메시지)

### 3. inventory: 폐기 소모
`production.scrapped` 를 받으면 그 생산 건의 RESERVED 예약 줄마다
- 예약 줄 CONSUMED, 부품 reserved −qty, 재고 이력 **SCRAP**(ref PRODUCTION), LOT 남은 수량을 입고 순으로 차감 (완성 때 차감과 같은 순서. 폐기라 찍은 LOT 은 쓰지 않는다)
- 완성차는 만들지 않는다
- RESERVED 줄이 없으면(이미 처리) 건너뛴다

### 4. order: 폐기 차량과 대체 차량
- 차량 상태 **SCRAPPED** 를 더한다. production 상태 SCRAPPED → 차량 SCRAPPED
- 품목 요약에서 SCRAPPED 차량은 빼고 계산한다. 대체 차량이 아직 안 왔으면 그 자리는 지금처럼 접수(RECEIVED)로 센다
- 품목 수량보다 많은 차량을 받지 않는 규칙(`ORDER_VEHICLE_OVERFLOW`)에서 SCRAPPED 차량은 세지 않는다 → 대체 차량이 들어올 자리가 생긴다
- 주문 상세에 폐기 차량도 보인다 (status SCRAPPED, vin 없음)
- 주문 취소 요청: 폐기 차량은 묻지 않는다(CANCEL_REQUESTED 로 바꾸지 않음). 취소할 수 있는지(차량을 다 아는지 `ORDER_NOT_READY`, 모두 취소됐는지)도 폐기 차량을 빼고 본다

### 5. simulator 불량률
- dev `factoryflow.simulator.defect-rate` 0.05 → **0.01** (로봇 공정마다. 차 한 대 폐기 확률 약 3%). APP_DEV_YML_SIMULATOR 시크릿 다시 등록 + simulator 재배포
- 로컬·테스트 기본값은 그대로

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 의장·검사 불량 | 로봇 공정과 같이 폐기 (불량 사유 필수는 그대로) |
| 폐기 뒤 늦게 온 설비 보고 | 무시 (끝난 작업지시) |
| 같은 mes.vehicle-scrapped 두 번 | ProcessedEventGuard + SCRAPPED 면 무시 → 대체 차는 한 대만 |
| 폐기된 차의 주문을 취소 요청 | 폐기 차량은 이미 끝, 대체 차량은 지금 취소 규칙대로 (발행 전이면 취소) |
| 취소 요청과 폐기가 엇갈림 (order 는 아직 생산 중으로 앎) | production 이 폐기된 생산 건을 `production.cancel-rejected(status SCRAPPED)` 로 답한다 → order 차량 SCRAPPED, 취소 회차가 끝난다 |
| 대체 차의 PLANNED 가 폐기 SCRAPPED 보다 먼저 옴 | 수량 초과(`ORDER_VEHICLE_OVERFLOW`)로 실패 → 재시도 때 반영 (같은 트랜잭션의 Outbox 순서라 드물다) |
| 대체 차도 불량 | 또 폐기하고 또 대체 (횟수 제한 없음) |
| 부품이 모자람 | 대체 차는 대기 줄에 선다 (구매 담당이 발주 제안으로 본다) |

## 상태
| 서비스 | 더하는 상태 |
|---|---|
| mes work_order | SCRAPPED (끝) |
| production | SCRAPPED (끝) |
| order 차량 | SCRAPPED (요약·수량에서 뺌) |
| inventory stock_history | SCRAP |
- 상태 컬럼은 모두 VARCHAR 라 마이그레이션 없음

## 관련 이벤트 (events.md, 사람이 적용)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| mes.vehicle-scrapped (새) | mes → production | productionId, workOrderId, process, defectReason |
| production.scrapped (새) | production → inventory | orderId, productionId |
| production.status-changed | production → order | status 에 SCRAPPED 추가 |
- 이벤트 추가라 one-way-door. /add-event 로 큐·DLQ 를 만든다

## 완료 조건 (테스트로 확인할 것)
- [x] mes: 작업자 DEFECT·설비 DEFECT 모두 작업지시 SCRAPPED, 설비 비움, 실적 DEFECT, mes.vehicle-scrapped. 재작업(PENDING) 없음
- [x] mes: SCRAPPED 작업지시는 배정·작업자 시작·설비 보고에서 빠짐
- [x] production: IN_PROGRESS·READY 생산 건 SCRAPPED + production.scrapped + 대체 생산 건(seq+1) PLANNED + material-requested. 두 번 받아도 대체 한 대
- [x] production: 제품·BOM 이 없으면 대체 생산 건 CANCELLED
- [x] inventory: production.scrapped → RESERVED 줄 CONSUMED, reserved −, SCRAP 이력, LOT 입고 순 차감, 완성차 없음
- [x] order: 폐기 차량 SCRAPPED, 대체 차량이 수량 초과 없이 추가, 요약에서 SCRAPPED 제외
- [ ] 수동(dev): 불량 → 폐기 → 대체 차 발행 대기 → 발행 → 완성까지 한 바퀴
