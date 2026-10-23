# 이벤트 계약 (사람만 수정)
공통 필드: eventId(UUID), traceId, version, occurredAt
이름: {발행 서비스}.{과거형} (예외: 가상 설비 simulator 는 equipment.*). 서비스: order, production(생산 계획), mes(현장 실행), inventory, purchase
Exchange: factoryflow.events (topic), 라우팅 키 = 이벤트 이름
기준: docs/planning/production-flow.md (기획서)

| 이벤트 | 발행 | 수신 | payload |
|---|---|---|---|
| order.created | order | production | orderId, products[{productId, qty}] |
| order.cancel-requested | order | production | orderId |
| order.cancelled | order | (없음, 이후 알림·출고) | orderId |
| production.material-requested | production | inventory | productionId, parts[{partId, qty}] |
| production.status-changed | production | order | orderId, productionId, productId, status |
| production.released | production | mes | productionId, orderId, productId |
| production.release-cancel-requested | production | mes | orderId, productionId |
| production.cancelled | production | inventory, order | orderId, productionId |
| production.cancel-rejected | production | order | orderId, productionId, reason, status |
| production.completed | production | inventory, order | orderId, productionId, productId, vin, scannedLots[{partId, lotNo}] |
| production.scrapped | production | inventory | orderId, productionId |
| mes.step-dispatched | mes | simulator | workOrderId, process, equipmentCode, dispatchSeq |
| mes.production-started | mes | production | productionId, workOrderId |
| mes.vehicle-completed | mes | production | productionId, workOrderId, vin, scannedLots[{partId, lotNo}] |
| mes.vehicle-scrapped | mes | production | productionId, workOrderId, process, defectReason |
| mes.work-order-cancelled | mes | production | productionId |
| mes.work-order-cancel-rejected | mes | production | productionId, status |
| inventory.reserved | inventory | production | productionId |
| inventory.reservation-pending | inventory | production | productionId, shortages[{partId, shortQty}] |
| inventory.reservation-failed | inventory | production | productionId, reason (PART_NOT_FOUND), partIds[] |
| purchase.received | purchase | inventory | purchaseId, partId, qty |
| equipment.step-started | simulator | mes | equipmentCode, workOrderId, process, dispatchSeq |
| equipment.step-completed | simulator | mes | equipmentCode, workOrderId, process, result, defectReason, dispatchSeq |
| equipment.status-changed | simulator | mes | equipmentCode, process, status |
| equipment.step-telemetry | simulator | mes | equipmentCode, workOrderId, process, dispatchSeq, samples[{at, values{이름: 값}}] (공정당 최대 300개, 잃어도 되는 데이터라 재전송 없음, docs/specs/process-telemetry.md) |
| shipment.completed | inventory | order | orderId, productionId, productId, vin |

## 흐름
고객 주문(order) → 차량 1대마다 생산 건(production, BOM 전개) → 부품 예약(inventory)
→ 부족하면 대기, 구매 담당이 부족 부품을 보고 구매(purchase, docs/specs/purchase-manual.md) → 입고 후 재예약 → 예약 완료(inventory.reserved)가 생산의 트리거
→ 생산 담당이 작업지시 발행(production.released) → mes 가 작업지시 5공정: 프레스·차체·도장은 mes 가 설비에 배정하고 설비(simulator)가 보고, 의장·검사는 작업자가 진행 → 첫 공정 시작을 production 에 알림(mes.production-started) → 검사 완료 시 VIN 발행(mes.vehicle-completed) → 생산 완료(production.completed) → 재고 차감·완성차 재고 → 출고
설비 고장·정지는 그 설비의 공정을 다른 설비로 재배정(생산 실패 없음, docs/specs/equipment-status.md), 취소는 첫 공정(프레스) 시작 전까지만 (보상: 예약 해제). 발행 전 차는 production 이, 발행한 차는 시작 여부를 아는 mes 가 판단한다 (production.release-cancel-requested → mes.work-order-cancelled / cancel-rejected, docs/specs/mes-split.md). 상세: docs/specs/saga-order-fulfillment.md

## 변경 규칙
- 필드 추가만 허용 (version 유지). 삭제/이름 변경은 새 version + 기존 버전 병행
- MES 분리(#134)로 production.step-dispatched 는 mes.step-dispatched 로 바뀌었다. 발행(mes)과 수신(simulator)을 같은 배포에서 함께 바꿔 병행하지 않는다 (운영 데이터 없음, dev 만)
