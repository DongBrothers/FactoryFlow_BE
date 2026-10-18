-- 쓰지 않는 컬럼 정리 (docs/specs/unused-columns.md, #123)
-- 소프트 삭제는 사람이 지울 수 있는 기준 데이터(part)에만 둔다
ALTER TABLE part_reservation DROP COLUMN is_deleted;
ALTER TABLE finished_vehicle DROP COLUMN is_deleted;
ALTER TABLE part_lot DROP COLUMN is_deleted;
-- 부품은 lot_id 의 LOT 이, 생산 건은 vin 의 완성차가 갖고 있다. 사용 기록은 vin, lot_id 로만 찾는다
ALTER TABLE part_lot_usage DROP COLUMN production_id, DROP COLUMN part_id;
