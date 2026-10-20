# 작업지시 수동 발행

## 목적
지금은 부품이 다 잡히면(`inventory.reserved`) 작업지시가 자동으로 나가고 바로 라인이 움직인다. 실제 공장처럼 **생산 담당이 작업지시를 발행해야 현장(MES)이 시작**하게 한다.
부품 확보는 지금처럼 자동이다. 주문이 오면 바로 예약하고, 모자라면 구매 담당이 부족 부품 조회로 본다 (수요가 생기는 즉시 자재를 준비하는 MRP 와 같은 흐름).

```
주문 접수 (영업)
  ↓ 자동
생산 건 + 부품 예약          ← 모자라면 구매 담당 발주·입고 (지금과 같음)
  ↓ 부품이 다 잡힘
READY = 작업지시 발행 대기    ← 바뀌는 곳: 여기서 멈춘다
  ↓ ★ 생산 담당: 작업지시 발행 (새 API)
작업지시 RELEASED + 공정 5개 → 배정 스케줄러·작업자 공정 (지금과 같음)
  ↓
검사 → VIN → 완성차 → 출고 (지금과 같음)
```

## 흐름

### 1. 부품이 다 잡히면 READY 에서 멈춘다 (production ← inventory.reserved)
- `ProductionService.markReserved`: 생산 건 READY + `production.status-changed(READY)` 까지만 한다. **작업지시를 만들지 않는다**
- 생산 건 상태와 이벤트는 그대로다. READY 의 뜻만 "부품 확보, 작업지시 발행 대기" 로 바뀐다 (작업지시를 발행한 뒤 첫 공정을 시작하기 전까지도 READY)
- 배정 스케줄러는 작업지시가 있는 차만 보므로, 발행 전 차는 배정되지 않는다 (코드 변경 없음)

### 2. 발행 대기 목록 `GET /production/productions/awaiting-release` (읽기 역할)
gateway `/api/production/productions/awaiting-release?lastId=&size=20`
```json
{ "items": [ { "productionId": 101, "orderId": 10, "productId": 1, "productCode": "AVANTE", "productName": "아반떼", "seq": 1 } ],
  "count": 1, "last": true, "nextCursor": null }
```
- READY 이고 작업지시가 아직 없는 생산 건. 먼저 생긴 순(생산 건 id 오름차순), 커서는 마지막 productionId (작업지시 목록과 같은 `CursorPage`)
- 제품 코드·이름을 붙인다 (생산 담당이 어떤 차인지 보고 고른다)

### 3. 작업지시 발행 `POST /production/productions/{productionId}/work-order` (PRODUCTION)
gateway `/api/production/productions/{productionId}/work-order`, 본문 없음
1. 생산 건을 잠그고 읽는다 (`PESSIMISTIC_FORCE_INCREMENT`: 행을 잠그고 version 을 바로 올린다)
2. READY 인지, 아직 발행하지 않았는지(`production.released_at` NULL) 확인
3. `released_at` = 지금 + `production.released { productionId, orderId, productId }`. 작업지시 1건(RELEASED)과 라우팅 순서대로 공정 5개(PENDING)는 이 이벤트를 받은 mes 가 만든다 (docs/specs/mes-split.md)
4. `202` `{ productionId, releasedAt }`. 작업지시는 mes 작업지시 목록(`GET /api/mes/work-orders`)에서 본다
- 생산 건 상태는 READY 그대로다
- mes 가 작업지시를 만든 뒤 다음 배정 바퀴(5초)에 프레스가 비어 있으면 배정된다

**취소와 동시에 오면**: 발행이 생산 건의 version 을 올리므로, 같은 차의 취소 수신(`order.cancel-requested`)이 먼저 읽은 상태로 저장하려 하면 충돌하고 재시도한다. 재시도는 발행된 차로 보고 mes 에 취소를 묻는다(`production.release-cancel-requested`, docs/specs/mes-split.md "4"). 취소가 먼저 끝났으면 발행은 READY 가 아니라 409 다.

### 4. 고객에게 보이는 상태 (order)
- 생산 건 READY 를 차량 **접수(RECEIVED)** 로 보인다 (지금은 생산 중). 작업지시를 발행하기 전 차가 고객에게 "생산 중" 으로 보이지 않게 한다
- 생산 중(IN_PRODUCTION)은 첫 공정(프레스)을 시작했을 때(IN_PROGRESS)부터다. 이때부터 취소도 안 된다 (지금 규칙과 같은 시점)

| production | order_vehicle (지금) | order_vehicle (바뀐 뒤) |
|---|---|---|
| PLANNED, WAITING_MATERIAL | RECEIVED | RECEIVED |
| READY | IN_PRODUCTION | **RECEIVED** |
| IN_PROGRESS | IN_PRODUCTION | IN_PRODUCTION |
| COMPLETED | COMPLETED | COMPLETED |
| CANCELLED | CANCELLED | CANCELLED |

- 이미 있는 order 데이터는 마이그레이션하지 않는다. order 는 생산 건이 READY 인지 IN_PROGRESS 인지 따로 갖고 있지 않아 IN_PRODUCTION 차량을 가려낼 수 없다. 배포 전부터 있던 READY 차는 이미 작업지시가 있어 곧 프레스가 시작되고 IN_PROGRESS 로 다시 맞춰진다

## 규칙 / 예외
| 상황 | 응답 |
|---|---|
| productionId 가 양수가 아님, lastId 음수, size 1~100 밖 | 400 |
| 생산 건 없음 | 404 `PRODUCTION_NOT_FOUND` |
| READY 가 아님 (부품 확보 전 PLANNED·WAITING_MATERIAL, 이미 시작·완료·취소) | 409 `PRODUCTION_NOT_READY` |
| 이미 작업지시가 있음 | 409 `WORK_ORDER_ALREADY_RELEASED` |
| PRODUCTION 이 아님 (발행) | 403 (gateway) |

- 발행 취소(작업지시 회수)는 하지 않는다. 발행한 차를 멈추려면 주문 취소를 쓴다 (프레스 배정 전이면 작업지시도 함께 취소된다, 지금과 같음)
- 여러 대 한 번에 발행, 발행 순서·우선순위 지정은 하지 않는다 (배정은 지금처럼 작업지시 id 순)
- 이벤트, 테이블 변경 없음

## 관련 이벤트 (events.md 참조)
| 이벤트 | 바뀌는 점 |
|---|---|
| inventory.reserved | production 이 받아 READY 로만 바꾸고 작업지시를 만들지 않는다 (payload 그대로) |
| production.status-changed | 그대로. order 가 READY 를 RECEIVED 로 매핑한다 |

## 바뀌는 명세
- docs/specs/production-reservation-result.md: READY 에서 작업지시를 만들지 않음
- docs/specs/saga-order-fulfillment.md: 성공 흐름 6번(작업지시 자동 발행) → 생산 담당 발행, 상태 매핑 표
- docs/specs/order-production-status.md: 매핑 표 (READY → RECEIVED)
- docs/specs/production-work-order.md: 작업지시가 생기는 때

## 완료 조건 (테스트로 확인할 것)
- [ ] inventory.reserved 수신: 생산 건 READY + status-changed(READY), 작업지시·공정 없음
- [ ] 발행: READY 생산 건에 released_at + production.released, 202 (작업지시 RELEASED + 공정 5개는 mes 가 만든다)
- [ ] 발행 뒤 배정 스케줄러가 프레스를 배정한다 (발행 전에는 배정하지 않음)
- [ ] 없는 생산 건 404, READY 아님 409 (PLANNED, WAITING_MATERIAL, IN_PROGRESS, CANCELLED), 이미 발행 409
- [ ] 발행 대기 목록: READY 이고 작업지시 없는 것만, id 순, 커서, 제품 코드·이름
- [ ] 발행된 READY 차의 취소: 생산 건·작업지시 함께 CANCELLED (지금과 같음)
- [ ] order: status-changed(READY) → 차량 RECEIVED, IN_PROGRESS → IN_PRODUCTION (READY 는 늘 취소할 수 있어 취소 거절에 READY 가 실려 오지 않는다)
- [ ] gateway: 발행은 PRODUCTION 만, 목록은 읽기 역할
- [ ] Swagger: 두 API 의 summary·응답 코드
