-- 제품 (고객이 주문하는 완성품)
CREATE TABLE product (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- BOM: 제품 1개에 필요한 부품과 수량
CREATE TABLE bom (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    product_id    BIGINT      NOT NULL,            -- 논리 FK: product.id
    part_id       BIGINT      NOT NULL,            -- 논리 참조: inventory_db.part.id
    qty_per_unit  INT         NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bom_product_part (product_id, part_id),
    KEY idx_bom_part (part_id),
    CONSTRAINT ck_bom_qty_per_unit CHECK (qty_per_unit > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 생산 건 (주문 속 제품 1종당 1건)
CREATE TABLE production (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_id      BIGINT      NOT NULL,            -- 논리 참조: order_db.customer_order.id
    product_id    BIGINT      NOT NULL,            -- 논리 FK: product.id
    qty           INT         NOT NULL,
    status        VARCHAR(20) NOT NULL,            -- PLANNED, WAITING_MATERIAL, READY, IN_PROGRESS, COMPLETED, CANCELLED
    started_at    DATETIME(6) NULL,
    completed_at  DATETIME(6) NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    is_deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_order_product (order_id, product_id),
    KEY idx_production_status (status),
    CONSTRAINT ck_production_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- processed_event: PK 를 id 로 통일, event_id 는 중복 불가
ALTER TABLE processed_event
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_processed_event_event_id (event_id);
