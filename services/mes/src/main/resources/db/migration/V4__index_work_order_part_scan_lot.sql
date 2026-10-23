-- 부품 계보는 mes 가 갖는다: LOT 으로 그 LOT 이 들어간 차를 찾는다 (리콜, docs/specs/part-genealogy.md, #176)
CREATE INDEX idx_work_order_part_scan_lot ON work_order_part_scan (lot_no);
