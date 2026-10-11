# 이벤트 계약 (사람만 수정)
공통 필드: eventId(UUID), traceId, version, occurredAt
Exchange: factoryflow.events (topic), 라우팅 키 = 이벤트 이름

| 이벤트 | 발행 | 수신 | payload |
|---|---|---|---|
| order.created | order | production | orderId, products[{productId, qty}] |
| order.cancelled | order | production | orderId |
| production.material-requested | production | inventory | productionId, parts[{partId, qty}] |
| production.cancelled | production | inventory | productionId |
| production.status-changed | production | order | orderId, productionId, productId, status |
| production.completed | production | order, inventory | orderId, productionId, productId, qty |
| inventory.reserved | inventory | production | productionId |
| inventory.reservation-pending | inventory | production | productionId, shortages[{partId, shortQty}] |
| inventory.shortage | inventory | purchase | partId, partCode, partName, shortQty |
| purchase.received | purchase | inventory | partId, qty |

## 흐름
고객 주문(order) → 생산 건(production, BOM 전개) → 부품 예약(inventory)
→ 부족하면 대기(WAITING_MATERIAL) + 자동 구매 요청(purchase) → 입고 후 재예약 → 생산 → 완료

## 변경 규칙
- 필드 추가만 허용 (version 유지). 삭제/이름 변경은 새 version + 기존 버전 병행
