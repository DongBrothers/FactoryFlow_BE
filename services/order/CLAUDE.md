# order 서비스
- DB: order_db (Flyway: src/main/resources/db/migration)
- 발행: order.created, order.cancelled
- 수신: inventory.reserved, inventory.reservation-failed, production.completed
- 진입점: controller/OrderController (HTTP), event/listener (이벤트) → service/OrderService
- 보상: inventory.reservation-failed 수신 시 주문 상태 FAILED
- 에러 코드: exception/OrderErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
