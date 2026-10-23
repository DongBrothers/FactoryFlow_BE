-- 부품 계보(VIN ↔ LOT)는 mes 가 갖는다 (의장에서 찍은 LOT, work_order_part_scan). inventory 는 재고 수량만 갖는다
-- (docs/specs/part-genealogy.md, #176). LOT 남은 수량과 재고 이력(stock_history)은 그대로다
DROP TABLE part_lot_usage;
