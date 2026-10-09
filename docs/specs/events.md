# 이벤트 계약 (사람만 수정)
공통 필드: eventId(UUID), traceId, version, occurredAt
Exchange: factoryflow.events (topic), 라우팅 키 = 이벤트 이름

| 이벤트 | 발행 | 수신 | payload |
|---|---|---|---|
| order.created | order | inventory | orderId, items[{partId, qty}] |
| order.cancelled | order | inventory | orderId |
| inventory.reserved | inventory | order, production | orderId |
| inventory.reservation-failed | inventory | order | orderId, reason |
| inventory.shortage | inventory | purchase | partId, shortQty |
| purchase.received | purchase | inventory | partId, qty |
| production.completed | production | order, inventory | orderId, qty |

## 변경 규칙
- 필드 추가만 허용 (version 유지). 삭제/이름 변경은 새 version + 기존 버전 병행
