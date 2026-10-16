-- 부품 LOT (docs/specs/part-lot.md). 입고 한 번에 한 줄. remaining_qty 는 아직 쓰지 않은 수량
CREATE TABLE part_lot (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    part_id        BIGINT      NOT NULL,           -- 논리 FK: part.id
    lot_no         VARCHAR(32) NOT NULL,           -- LOT-{yyMMdd}-{purchaseId, 6자리 미만은 0 채움}. Long 최대 19자리까지
    purchase_id    BIGINT      NOT NULL,           -- 논리 참조: purchase_db.purchase.id
    received_qty   INT         NOT NULL,
    remaining_qty  INT         NOT NULL,
    received_at    DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_part_lot_lot_no (lot_no),
    UNIQUE KEY uk_part_lot_purchase (purchase_id),
    KEY idx_part_lot_part (part_id, id),
    CONSTRAINT ck_part_lot_received_qty CHECK (received_qty > 0),
    CONSTRAINT ck_part_lot_remaining_qty CHECK (remaining_qty >= 0 AND remaining_qty <= received_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 완성차에 쓰인 부품 LOT (추가만 하고 수정하지 않음). lot_id 가 NULL 이면 LOT 도입 전 재고
CREATE TABLE part_lot_usage (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    vin            VARCHAR(17) NOT NULL,
    production_id  BIGINT      NOT NULL,           -- 논리 참조: production_db.production.id
    part_id        BIGINT      NOT NULL,           -- 논리 FK: part.id
    lot_id         BIGINT      NULL,               -- 논리 FK: part_lot.id
    qty            INT         NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_part_lot_usage_vin (vin),
    KEY idx_part_lot_usage_lot (lot_id),
    CONSTRAINT ck_part_lot_usage_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
