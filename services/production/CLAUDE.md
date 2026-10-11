# production 서비스
- DB: production_db (Flyway: src/main/resources/db/migration). 테이블: product, bom, production (주문 속 제품 1종당 생산 1건)
- 발행: production.material-requested, production.cancelled, production.status-changed, production.completed
- 수신: order.created, order.cancelled, inventory.reserved, inventory.reservation-pending
- 진입점: controller/ProductionController (HTTP), event/listener (이벤트) → service/ProductionService
- 보상: order.cancelled 수신 시 생산 건 CANCELLED + production.cancelled 로 부품 예약 해제
- 에러 코드: exception/ProductionErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
