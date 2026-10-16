# inventory 서비스
- DB: inventory_db (Flyway: src/main/resources/db/migration). 테이블: part (재고 수량 포함), part_reservation, stock_history, 완성차 재고(finished_vehicle), 부품 LOT(part_lot), 완성차에 쓰인 LOT(part_lot_usage)
- part.reserved 는 part_reservation 의 RESERVED 합계와 같다. 예약 변경과 같은 트랜잭션에서 갱신
- 발행: inventory.reserved, inventory.reservation-pending, inventory.shortage, shipment.completed
- 수신: production.material-requested, production.cancelled, production.completed, purchase.received
- 완료: production.completed 수신 시 예약 부품 CONSUMED, 완성차 재고 증가(VIN), 소모 부품 LOT ↔ VIN 연결
- LOT: 입고 한 번에 LOT 하나, 생산 완료 때 LOT 없는 재고부터 그다음 LOT 입고 순으로 써서 기록. 조회 GET /inventory/finished-vehicles/{vin}/part-lots, GET /inventory/part-lots/{lotNo}/vehicles (docs/specs/part-lot.md)
- 조회: 부품 재고 GET /inventory/parts, LOT 별 재고 GET /inventory/parts/{partId}/lots, 완성차 목록 GET /inventory/finished-vehicles (최신 먼저, lastVin 으로 다음 페이지) (docs/specs/list-apis.md)
- 출고: POST /inventory/finished-vehicles/{vin}/ship 으로 완성차 한 대 SHIPPED + shipment.completed (docs/specs/shipment.md)
- 보상: production.cancelled 수신 시 예약 RELEASED + available 복구. 재고 변동은 모두 stock_history 에 기록
