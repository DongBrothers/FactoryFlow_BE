# inventory 서비스
- DB: inventory_db (Flyway: src/main/resources/db/migration)
- 발행: inventory.reserved, inventory.reservation-failed, inventory.shortage
- 수신: order.created, order.cancelled, purchase.received, production.completed
- 진입점: controller/InventoryController (HTTP), event/listener (이벤트) → service/InventoryService
- 보상: 미정 (정의 시 docs/specs 에 먼저 작성)
- 에러 코드: exception/InventoryErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
