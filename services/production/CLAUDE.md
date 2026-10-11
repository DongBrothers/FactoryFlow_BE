# production 서비스
- DB: production_db (Flyway: src/main/resources/db/migration). 테이블: product, bom, production (주문 속 제품 1종당 생산 1건)
- 발행: production.material-requested, production.cancelled, production.status-changed, production.completed, production.failed, production.cancel-rejected
- 수신: order.created, order.cancelled, inventory.reserved, inventory.reservation-pending
- 진입점: controller/ProductionController (HTTP), event/listener (이벤트) → service/ProductionService
- 보상: order.cancelled 수신 시 생산 시작 전(PLANNED, WAITING_MATERIAL, READY)이면 CANCELLED + production.cancelled 로 예약 해제, 아니면 production.cancel-rejected
- 재생산: 불량 시 WAITING_MATERIAL 로 되돌리고 production.failed (docs/specs/saga-order-fulfillment.md)
- 에러 코드: exception/ProductionErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
