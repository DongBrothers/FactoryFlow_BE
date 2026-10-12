# inventory 서비스
- DB: inventory_db (Flyway: src/main/resources/db/migration). 테이블: part (재고 수량 포함), part_reservation, stock_history
- part.reserved 는 part_reservation 의 RESERVED 합계와 같다. 예약 변경과 같은 트랜잭션에서 갱신
- 발행: inventory.reserved, inventory.reservation-pending, inventory.shortage
- 수신: production.material-requested, production.cancelled, production.completed, production.failed, purchase.received
- 보상: production.cancelled 수신 시 예약 RELEASED + available 복구. production.failed 수신 시 RESERVED 분 CONSUME 후 같은 예약 줄로 재예약. 재고 변동은 모두 stock_history 에 기록
