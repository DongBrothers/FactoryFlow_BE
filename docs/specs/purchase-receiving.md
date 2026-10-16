# 구매·입고 (inventory.shortage → 자동 구매 → 발주 → 입고 → 대기 줄 재예약)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 3(부족 → 구매)과 5(입고 → 재예약)다 (#95).
지금은 부족(`inventory.shortage`)을 받는 쪽이 없어 부품이 모자란 차가 WAITING_MATERIAL(부품 대기)에서 멈춘다.
purchase 가 부족을 자동 구매 건으로 받고, 구매 담당자가 발주·입고 처리하면 inventory 가 재고를 늘려 기다리던 차에 먼저 온 순서대로 부품을 채운다.
부품이 다 찬 차는 `inventory.reserved` 로 production 에 알려지고, production 은 지금 규칙대로 작업지시를 낸다.

수동 구매(MANUAL, 담당자가 부품과 수량을 직접 넣음)와 공급업체 등록·수정, 구매 취소, 부품 LOT(#76)는 이 작업에 넣지 않는다.

## 흐름

### 1. 부족 → 자동 구매 (purchase ← inventory.shortage)
1. purchase 가 큐 `purchase.inventory-shortage` 로 받는다 (ProcessedEventGuard, 처리 기록과 같은 트랜잭션)
2. 같은 부품의 AUTO + REQUESTED 구매 건이 있으면 그 qty 에 shortQty 를 더한다
3. 없으면 새 구매 건: `{ partId, partCode, partName(스냅샷), qty = shortQty, source AUTO, status REQUESTED }`
   - 이미 발주(ORDERED)한 건에는 더하지 않는다 (공급업체에 이미 보낸 수량이 바뀌면 안 됨). 새 건을 만든다
4. 이벤트는 보내지 않는다

### 2. 구매 담당 API (purchase, 밖 주소 `/api/purchases/...`)
| API | 하는 일 |
|---|---|
| `GET /purchases?status=REQUESTED&lastId=0&size=20` | 상태별 구매 목록 (id 커서). status 는 REQUESTED, ORDERED, RECEIVED |
| `GET /purchases/suppliers` | 공급업체 목록 (발주 때 고른다) |
| `POST /purchases/{purchaseId}/order` `{ supplierId }` | 발주: REQUESTED → ORDERED (공급업체, ordered_at) |
| `POST /purchases/{purchaseId}/receive` | 입고: ORDERED → RECEIVED (received_at) + `purchase.received { purchaseId, partId, qty }` |

| 확인 | 실패 |
|---|---|
| 구매 건 있음 | 404 `PURCHASE_NOT_FOUND` |
| 발주: 공급업체 있음 | 404 `SUPPLIER_NOT_FOUND` |
| 발주는 REQUESTED 에서만, 입고는 ORDERED 에서만 | 409 `INVALID_PURCHASE_STATUS` |
| 다른 요청이나 부족 합치기가 먼저 바꿈 (낙관적 락) | 409 `PURCHASE_CONFLICT` |
| 형식 오류 (supplierId 없음·1 미만, status 값, lastId·size 범위) | 400 |

- 수량은 구매 건 전체를 한 번에 입고한다 (부분 입고 없음)
- 응답: 구매 건 `{ purchaseId, partId, partCode, partName, qty, status, source, supplierId, orderedAt, receivedAt }`

### 3. 입고 → 재고 증가와 대기 줄 재예약 (inventory ← purchase.received)
1. inventory 가 큐 `inventory.purchase-received` 로 받는다 (ProcessedEventGuard, 처리 기록과 같은 트랜잭션)
2. 같은 구매 건의 입고 이력(stock_history RECEIVE, ref PURCHASE, purchaseId)이 이미 있으면 건너뛴다 (eventId 만 다른 재발행)
3. 부품을 읽는다 (예약과 같은 `OPTIMISTIC_FORCE_INCREMENT`). 없으면 `PART_NOT_FOUND` → 재시도 → DLQ
4. available +qty, stock_history RECEIVE (ref PURCHASE, purchaseId)
5. 그 부품의 WAITING 줄을 id 순서(먼저 기다린 순서)로 본다
   - available ≥ 줄의 qty 면 RESERVED (available −, reserved +, stock_history RESERVE ref PRODUCTION)
   - 모자라면 **거기서 멈춘다.** 뒤의 작은 줄이 들어갈 수 있어도 채우지 않는다 (먼저 기다린 차가 먼저, 예약 때 새치기 금지와 같은 규칙)
6. 이번에 RESERVED 가 된 줄의 생산 건마다, 그 생산 건에 WAITING 줄이 더 없으면 `inventory.reserved { productionId }`
   - 다른 부품을 아직 기다리면 보내지 않는다 (그 부품이 입고될 때 보낸다)
7. 남은 수량은 available 로 남는다 (다음 예약에 쓴다)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| shortage 를 두 번 받음 | 같은 eventId 는 Guard. eventId 가 다르면 수량이 두 번 합쳐진다 (inventory 의 shortQty 가 증가분만 보내므로 정상 흐름에선 같은 부족이 두 번 오지 않음) |
| 부족 합치기와 발주가 동시에 같은 구매 건을 바꿈 | 구매 건 version 으로 한쪽만 반영. 리스너가 지면 재시도 때 ORDERED 를 보고 새 건을 만든다. API 가 지면 409 |
| 같은 구매 건의 received 를 두 번 받음 | Guard 또는 입고 이력으로 한 번만 반영 |
| 입고 수량이 기다리는 수량보다 많음 | 남는 만큼 available |
| 입고된 부품을 기다리는 줄이 없음 | available 만 늘어난다 |
| 기다리던 생산 건이 그사이 취소됨 (#75) | 취소 처리가 WAITING 줄을 RELEASED 로 바꾸므로 여기서는 보이지 않는다 |
| 입고 처리 중 같은 부품의 새 예약 요청이 동시에 옴 | 부품 version 으로 한쪽만 반영, 늦은 쪽이 재시도 (예약과 같음) |

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| purchase (V3) | `purchase.version` (BIGINT NOT NULL DEFAULT 0). 공급업체 기본 데이터 3곳 (현대모비스, 한국타이어, 현대트랜시스) |
| inventory | 없음 (stock_history RECEIVE / ref PURCHASE 는 이미 있음) |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| inventory.shortage | inventory → purchase (`purchase.inventory-shortage`) | partId, partCode, partName, shortQty |
| purchase.received | purchase → inventory (`inventory.purchase-received`) | **purchaseId**, partId, qty |
`purchase.received` 에 purchaseId 를 더한다 (필드 추가, events.md 사람 적용). 입고 이력의 ref 와 같은 입고 중복 판단에 쓴다.
`inventory.reserved` 는 지금 계약 그대로다.

## 완료 조건 (테스트로 확인할 것)
purchase
- [ ] shortage → AUTO·REQUESTED 구매 건 (스냅샷), 같은 부품 REQUESTED 건이 있으면 합침, ORDERED 건에는 합치지 않고 새 건
- [ ] 같은 shortage 두 번 → 한 번만, 실제 브로커 경유 수신
- [ ] 발주: REQUESTED → ORDERED(공급업체, 시각), 입고: ORDERED → RECEIVED(시각) + purchase.received outbox(purchaseId, partId, qty)
- [ ] 상태가 맞지 않으면 409, 없는 구매·공급업체 404, 형식 오류 400, 동시 변경 409
- [ ] 목록은 상태별 커서, 공급업체 목록
inventory
- [ ] received → available +qty, RECEIVE 이력(ref PURCHASE), 대기 줄을 먼저 온 순서로 RESERVED(RESERVE 이력), 앞 줄이 안 차면 뒤 줄도 그대로
- [ ] 줄이 다 찬 생산 건만 inventory.reserved, 다른 부품을 기다리는 생산 건은 보내지 않음
- [ ] 같은 입고 두 번(같은 eventId / 다른 eventId) → 한 번만, 없는 부품 DLQ
- [ ] part.reserved = RESERVED 줄 합계가 유지된다
