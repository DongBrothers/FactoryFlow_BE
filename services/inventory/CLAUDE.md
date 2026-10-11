# inventory 서비스
- DB: inventory_db (Flyway: src/main/resources/db/migration). 테이블: part (재고 수량 포함), part_reservation, stock_history
- part.reserved 는 part_reservation 의 RESERVED 합계와 같다. 예약 변경과 같은 트랜잭션에서 갱신
- 발행: inventory.reserved, inventory.reservation-pending, inventory.shortage
- 수신: production.material-requested, production.cancelled, production.completed, production.failed, purchase.received
- 진입점: controller/{도메인}Controller (HTTP), 내부 API 는 Internal{도메인}Controller, event/listener (이벤트) → service/{도메인}Service. 이름 규칙과 Swagger 문서는 루트 CLAUDE.md
- 보상: production.cancelled 수신 시 예약 RELEASED + available 복구. production.failed 수신 시 RESERVED 분 CONSUME 후 같은 예약 줄로 재예약. 재고 변동은 모두 stock_history 에 기록
- 에러 코드: exception/InventoryErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
