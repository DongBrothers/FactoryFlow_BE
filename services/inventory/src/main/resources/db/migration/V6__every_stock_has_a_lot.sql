-- 모든 재고에 LOT 이 있다 (docs/specs/part-lot.md, #116). LOT 없는 재고를 초기 재고 LOT 으로 만든다
-- 초기 재고 LOT 은 구매 건이 없다
ALTER TABLE part_lot MODIFY COLUMN purchase_id BIGINT NULL;

-- LOT 은 입고 시각(같으면 id) 순으로 쓴다. 초기 재고 LOT 은 id 가 기존 LOT 보다 크므로 입고 시각으로 순서를 잡는다
CREATE INDEX idx_part_lot_part_received ON part_lot (part_id, received_at, id);

-- 부품마다 LOT-INIT-{부품 id}: 남은 수량 = 창고 실물 − 다른 LOT 남은 수량, 받은 수량 = 남은 수량 + LOT 없이 이미 쓴 수량
-- 가장 오래된 재고라 입고 시각은 그 부품의 가장 이른 LOT 보다 1초 앞 (LOT 이 없으면 지금)
INSERT INTO part_lot (part_id, lot_no, purchase_id, received_qty, remaining_qty, received_at, created_at, updated_at)
SELECT t.part_id, CONCAT('LOT-INIT-', t.part_id), NULL, t.remaining + t.used, t.remaining,
       COALESCE(t.first_received_at - INTERVAL 1 SECOND, NOW(6)), NOW(6), NOW(6)
FROM (
    SELECT p.id AS part_id,
           (SELECT MIN(l.received_at) FROM part_lot l WHERE l.part_id = p.id) AS first_received_at,
           GREATEST(0, p.available + p.reserved
               - COALESCE((SELECT SUM(l.remaining_qty) FROM part_lot l WHERE l.part_id = p.id), 0)) AS remaining,
           COALESCE((SELECT SUM(u.qty) FROM part_lot_usage u WHERE u.part_id = p.id AND u.lot_id IS NULL), 0) AS used
    FROM part p
) t
WHERE t.remaining + t.used > 0;

-- LOT 없이 남은 사용 기록은 그 부품의 초기 재고 LOT 으로 옮기고, 사용 기록의 LOT 을 필수로 한다
UPDATE part_lot_usage u
JOIN part_lot l ON l.part_id = u.part_id AND l.lot_no = CONCAT('LOT-INIT-', u.part_id)
SET u.lot_id = l.id
WHERE u.lot_id IS NULL;
ALTER TABLE part_lot_usage MODIFY COLUMN lot_id BIGINT NOT NULL;
