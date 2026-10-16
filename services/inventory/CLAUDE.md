# inventory 서비스
- DB: inventory_db (Flyway: src/main/resources/db/migration). 테이블: part (재고 수량 포함), part_reservation, stock_history, 완성차 재고(VIN), 부품 LOT ↔ VIN (해당 하위 이슈에서 추가)
- part.reserved 는 part_reservation 의 RESERVED 합계와 같다. 예약 변경과 같은 트랜잭션에서 갱신
- 발행: inventory.reserved, inventory.reservation-pending, inventory.shortage, shipment.completed
- 수신: production.material-requested, production.cancelled, production.completed, purchase.received
- 완료: production.completed 수신 시 예약 부품 CONSUMED, 완성차 재고 증가(VIN), 소모 부품 LOT ↔ VIN 연결
- 출고: POST /inventory/finished-vehicles/{vin}/ship 으로 완성차 한 대 SHIPPED + shipment.completed (docs/specs/shipment.md)
- 보상: production.cancelled 수신 시 예약 RELEASED + available 복구. 재고 변동은 모두 stock_history 에 기록
