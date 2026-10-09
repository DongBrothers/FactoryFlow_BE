# purchase 서비스
- DB: purchase_db (Flyway: src/main/resources/db/migration)
- 발행: purchase.received
- 수신: inventory.shortage
- 진입점: controller/PurchaseController (HTTP), event/listener (이벤트) → service/PurchaseService
- 보상: 미정 (정의 시 docs/specs 에 먼저 작성)
- 에러 코드: exception/PurchaseErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
