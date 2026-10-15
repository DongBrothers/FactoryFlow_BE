# 기획서: 생산 흐름 (ERP + MES)

이 문서가 생산 흐름의 기준이다. 명세(docs/specs/)와 이벤트 계약(docs/specs/events.md), 서비스별 CLAUDE.md 는 이 문서와 맞아야 한다.
어긋나면 이 문서를 먼저 고치고(사람 확인) 나머지를 맞춘다.

## 생산 흐름
```
주문 (order.created)
   ↓ production 이 차량 1대마다 생산 건을 만들고 BOM 으로 필요한 부품을 요청
재고 예약 완료 (inventory.reserved)               ← 생산의 트리거
   ↓
production(MES): 라우팅 기준으로 5개 공정 작업지시 자동 생성
   ↓
라인 작업자가 공정별로 시작·종료, 양품·불량 입력
   프레스 → 차체(용접) → 도장 → 의장(조립) → 검사
   ↓
검사 완료 → VIN(차대번호) 발행 → 생산 완료 (production.completed)
   ↓
재고: 투입 부품 차감, 완성차 재고 증가, 부품 LOT ↔ VIN 연결
   ↓
출고 (shipment.completed)
```

| 질문 | 답 |
|---|---|
| 생산의 트리거는? | `inventory.reserved` (부품 예약이 끝나면 production 이 작업지시를 자동 생성) |
| 실제 생산은 누가? | production 서비스(MES)가 작업지시, 공정 진행, 실적, VIN 발행을 관리 |
| 공정 진행은 누가? | 라인 작업자가 공정별 시작·종료와 실적을 입력 (관리 화면 또는 API) |
| 시뮬레이터는? | 가상의 공장 설비. 설비 상태만 발생시킨다 (가동·정지·고장 → `equipment.status-changed` → production 이 저장) |

- 설비가 고장 나면 그 공정에서 만들던 차량의 생산이 실패하고(`production.failed`), 재고가 예약을 풀어 주는 보상으로 이어진다
- 설비 상태 기록은 나중에 설비 가동률(OEE) 대시보드의 데이터가 된다

## 공정 데이터 구조
`work_order` 1 : N `work_order_step` (공정 5개) 1 : N `production_result`

- 공정 순서(라우팅)는 모든 차종 공통: 프레스(PRESS) → 차체(BODY) → 도장(PAINT) → 의장(ASSEMBLY) → 검사(INSPECTION)
- 실적(production_result)은 공정을 끝낼 때마다 한 줄: 양품 또는 불량(사유)

## 취소 기준
첫 공정(프레스) 시작 전까지만 취소할 수 있다. 생산 순서가 확정되면 협력업체가 그 순서대로 부품을 보내기 때문이다.

## 정한 것 (2026-10-15)
기획서에 없던 부분을 이렇게 정했다.

| 항목 | 결정 |
|---|---|
| 생산 단위 | 차량 1대 = 생산 건 1건 = 작업지시 1건. 주문에서 같은 차 3대면 생산 건 3건. VIN, 취소, 실패를 차량마다 판단한다 |
| 공정 불량 | 실적에 불량으로 기록하고 작업자가 같은 공정을 다시 한다 (재작업). 부품 재예약이나 이벤트는 없다 |
| 설비 고장 | 그 공정을 진행 중인 차량 생산을 실패(FAILED)시키고 `production.failed` → inventory 가 예약 해제. 자동 재생산은 하지 않는다 |
| 취소 | order 가 `order.cancel-requested` → production 이 차량마다 판단해 `production.cancelled`(승인, 예약 해제도 이것으로) 또는 `production.cancel-rejected` → order 가 승인된 품목을 취소하고 `order.cancelled` |
| 이벤트 이름 | 앞자리는 발행 서비스로 한다 (기존 규칙). 기획서의 stock.reserved, stock.shortage, material.received, cancel.requested 는 각각 `inventory.reserved`, `inventory.shortage`, `purchase.received`, `order.cancel-requested` 로 쓴다. 설비 이벤트는 가상 설비 도메인이라 `equipment.status-changed` |
| simulator 저장소 | 이벤트는 Outbox 로만 발행하므로 simulator 도 Outbox 용 DB 를 둔다 |

## 아직 정하지 않은 것
해당 하위 이슈를 시작할 때 정한다.
- 출고(`shipment.completed`): 누가 출고를 처리하고 발행하는지 (inventory 의 완성차 출고 vs order)
- 부품 LOT: 입고(`purchase.received`) 때 LOT 번호를 붙이는 방식과, 소모 때 LOT ↔ VIN 을 남기는 위치
- VIN 형식: 17자 규칙(WMI·VDS·VIS)을 어디까지 따를지
- 작업자 API 권한: 어떤 역할이 공정을 시작·종료할 수 있는지
