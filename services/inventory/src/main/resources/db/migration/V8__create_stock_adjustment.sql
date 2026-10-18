-- 재고 조정 (docs/specs/stock-adjustment.md, #129). 늘리면 qty 양수, 줄이면 음수. 추가만 하고 고치지 않는다 (잘못했으면 반대로 한 번 더)
-- 재고 이력(stock_history)의 ref_type ADJUSTMENT, ref_id 와 조정 LOT 번호(LOT-ADJ-{id})가 이 id 를 쓴다
CREATE TABLE stock_adjustment (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    part_id      BIGINT       NOT NULL,           -- 논리 FK: part.id
    qty          INT          NOT NULL,
    reason       VARCHAR(255) NOT NULL,
    adjusted_by  VARCHAR(36)  NOT NULL,           -- 조정한 직원 public_id (gateway 의 X-User-Id)
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_stock_adjustment_part (part_id, created_at),
    CONSTRAINT ck_stock_adjustment_qty CHECK (qty <> 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
