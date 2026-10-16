# 흐름이 조용히 멈추는 경우 (주문 뒤 제품 삭제, BOM 부품 없음, VIN 자리 초과)

## 목적
지금은 아래 경우 차나 품목이 멈추고, 로그나 DLQ 를 봐야만 안다 (#103).
멈추는 대신 **차를 취소 상태로 끝내** 주문 상세에서 보이게 하고, VIN 자리 초과는 원인을 알 수 있는 오류 코드로 남긴다.

| 경우 | 지금 | 바꾼 뒤 |
|---|---|---|
| S8 주문 뒤 제품·BOM 삭제 | 그 품목을 건너뜀 (`SAGA_SKIPPED` 로그), 품목은 계속 RECEIVED | 수량만큼 생산 건을 CANCELLED 로 만들고 order 에 알림 → 품목 CANCELLED |
| S18 BOM 부품이 inventory 에 없음 | `PART_NOT_FOUND` → 재시도 → DLQ, 차는 PLANNED | inventory 가 `inventory.reservation-failed` → production 이 생산 건 CANCELLED + `production.cancelled` → 차량 CANCELLED |
| VIN 자리 초과 | IllegalArgumentException (작업자 API 500, 설비 보고 DLQ) | 오류 코드 `VIN_RANGE_EXCEEDED` |

- 주문 전체를 실패시키지 않는다. 문제가 없는 품목·차는 그대로 진행한다 (지금 규칙)
- 고객에게 따로 알리는 기능(알림)은 넣지 않는다. 주문 상세에서 CANCELLED 로 보인다

## 흐름

### 1. S8: 주문 뒤 제품·BOM 삭제 (production ← order.created)
`ProductionService.planAll` 에서 제품이 없거나 BOM 이 없으면 (지금 건너뛰던 곳)
1. 그 제품의 수량만큼 생산 건을 **CANCELLED 로** 만든다 (seq 1..qty, 같은 주문·제품·seq 고유키 그대로라 다시 받아도 한 번)
2. 생산 건마다 `production.status-changed { status: CANCELLED }` (부품 요청은 보내지 않는다)
3. ERROR 로그는 남긴다: `SAGA_REJECTED orderId= productId= reason=PRODUCT_NOT_FOUND|BOM_NOT_FOUND`

order 는 지금 규칙대로 받는다: 처음 보는 생산 건이라 품목에 차량을 만들고 CANCELLED 로 바꾼 뒤 요약 → 품목의 차가 모두 취소라 품목 CANCELLED (요약 규칙 1), 주문은 나머지 품목으로 요약.

### 2. S18: BOM 부품이 inventory 에 없음
**inventory** (← production.material-requested, `PartReservationService.reserve`)
- 요청한 부품 중 없거나 삭제된 부품이 있으면, 예약 줄을 하나도 만들지 않고 `inventory.reservation-failed { productionId, reason: PART_NOT_FOUND, partIds }` (partIds = 없는 부품, id 순)
- 처리 오류로 던지지 않는다 (DLQ 로 가지 않음). WARN 로그
- 같은 요청을 다시 받으면 지금처럼 예약 줄 유무로 건너뛰는데, 실패하면 줄이 없으므로 다시 실패 이벤트를 보낸다 (production 이 한 번만 반영)

**production** (← inventory.reservation-failed, 큐 `production.inventory-reservation-failed`)
- PLANNED 생산 건 → CANCELLED + `production.cancelled { orderId, productionId }` (order 차량 CANCELLED, inventory 는 풀 줄이 없어 그대로). ERROR 로그 `SAGA_REJECTED productionId= reason=PART_NOT_FOUND partIds=`
- 이미 CANCELLED → 그대로 (INFO). 그 밖의 상태 → 무시 (INFO, 부품을 잡은 적 없는 차만 이 이벤트를 받는다)

### 3. VIN 자리 초과 (production, 검사 양품 → 완료)
- `Vin` 에 자리 확인(`Vin.fits(productId, workOrderId)`)을 두고, 넘으면 service 가 `VIN_RANGE_EXCEEDED` (500, "차대번호 자리를 넘었습니다")
- 작업자 API: 500 `VIN_RANGE_EXCEEDED`, 공정 완료가 롤백된다 (지금처럼 아무것도 바뀌지 않음)
- 설비 보고: 처리 오류 → 재시도 → DLQ, 로그에 `VIN_RANGE_EXCEEDED`
- 자리 자체를 늘리는 것(VIN 형식 변경)은 넣지 않는다

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| S8 order.created 를 다시 받음 | 생산 건(주문·제품·seq)이 이미 있어 건너뜀 (지금 규칙) |
| S8 상태 알림이 order 에 두 번 | 이미 CANCELLED 차량은 그대로 |
| S18 실패 이벤트를 두 번 받음 | 같은 eventId 는 Guard, 다른 eventId 면 이미 CANCELLED 라 그대로 |
| S18 취소 요청 회차 중 | 차량은 CANCEL_REQUESTED → production.cancelled 로 CANCELLED, 회차 끝내기는 지금 규칙 |
| S18 부품이 나중에 등록됨 | 이미 취소된 차는 되살리지 않는다 (다시 주문) |

### 테이블 변경
없음

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| **inventory.reservation-failed** (새로) | inventory → production (`production.inventory-reservation-failed`) | productionId, reason (PART_NOT_FOUND), partIds |
| production.status-changed | production → order | orderId, productionId, productId, status (CANCELLED 를 S8 에서도 씀) |
| production.cancelled | production → inventory, order | orderId, productionId |
events.md 에 `inventory.reservation-failed` 행 추가 (사람 적용).

## 완료 조건 (테스트로 확인할 것)
production
- [ ] S8: 없는 제품·BOM 없는 제품은 수량만큼 CANCELLED 생산 건 + status-changed(CANCELLED), 부품 요청 없음, 다른 제품은 그대로
- [ ] S8: 같은 order.created 두 번 → 한 번
- [ ] S18: reservation-failed → PLANNED 생산 건 CANCELLED + production.cancelled, 이미 취소·다른 상태면 그대로, 중복 한 번, 실제 브로커 경유
- [ ] VIN: 자리 확인 단위 테스트, 작업자 API 500 VIN_RANGE_EXCEEDED 이고 아무것도 바뀌지 않음
inventory
- [ ] S18: 없는·삭제된 부품이 있으면 예약 줄 없음, 재고 그대로, reservation-failed outbox (없는 partIds), DLQ 아님
order
- [ ] S8: 처음 보는 생산 건의 status-changed(CANCELLED) → 차량 생성 후 CANCELLED, 품목 모두 취소면 품목 CANCELLED (지금 규칙 확인)
