-- 차에 들어간 LOT 이 의장 공정에서 찍은 것(SCANNED)인지, 입고 순으로 배정한 것(FIFO)인지 (docs/specs/part-lot-scan.md, #170).
-- 지금까지의 기록은 모두 입고 순 배정이다
ALTER TABLE part_lot_usage
    ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'FIFO';
