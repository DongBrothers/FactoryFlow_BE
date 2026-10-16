# 이벤트 계약 (사람만 수정)
공통 필드: eventId(UUID), traceId, version, occurredAt
이름: {발행 서비스}.{과거형} (예외: 가상 설비 simulator 는 equipment.*)
Exchange: factoryflow.events (topic), 라우팅 키 = 이벤트 이름
기준: docs/planning/production-flow.md (기획서)

| 이벤트 | 발행 | 수신 | payload |
|---|---|---|---|
| order.created | order | production | orderId, products[{productId, qty}] |
| order.cancel-requested | order | production | orderId |
| order.cancelled | order | (없음, 이후 알림·출고) | orderId |
| production.material-requested | production | inventory | productionId, parts[{partId, qty}] |
| production.status-changed | production | order | orderId, productionId, productId, status |
| production.step-dispatched | production | simulator | workOrderId, process, equipmentCode |
| production.cancelled | production | inventory, order | orderId, productionId |
| production.cancel-rejected | production | order | orderId, productionId, reason, status |
| production.completed | production | inventory, order | orderId, productionId, productId, vin |
| inventory.reserved | inventory | production | productionId |
| inventory.reservation-pending | inventory | production | productionId, shortages[{partId, shortQty}] |
| inventory.shortage | inventory | purchase | partId, partCode, partName, shortQty |
| purchase.received | purchase | inventory | purchaseId, partId, qty |
| equipment.step-started | simulator | production | equipmentCode, workOrderId, process |
| equipment.step-completed | simulator | production | equipmentCode, workOrderId, process, result, defectReason |
| equipment.status-changed | simulator | production | equipmentCode, process, status |
| shipment.completed | inventory | order | orderId, productionId, productId, vin |

## 흐름
고객 주문(order) → 차량 1대마다 생산 건(production, BOM 전개) → 부품 예약(inventory)
→ 부족하면 대기 + 자동 구매(purchase) → 입고 후 재예약 → 예약 완료(inventory.reserved)가 생산의 트리거
→ 작업지시 5공정: 프레스·차체·도장은 production 이 설비에 배정하고 설비(simulator)가 보고, 의장·검사는 작업자가 진행 → 검사 완료 시 VIN 발행, 생산 완료 → 재고 차감·완성차 재고 → 출고
설비 고장·정지는 그 설비의 공정을 다른 설비로 재배정(생산 실패 없음, docs/specs/equipment-status.md), 취소는 첫 공정(프레스) 배정 전까지만 (보상: 예약 해제). 상세: docs/specs/saga-order-fulfillment.md

## 변경 규칙
- 필드 추가만 허용 (version 유지). 삭제/이름 변경은 새 version + 기존 버전 병행
