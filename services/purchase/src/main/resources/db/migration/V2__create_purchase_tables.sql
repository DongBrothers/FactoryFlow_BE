-- 공급업체
CREATE TABLE supplier (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    contact     VARCHAR(100) NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 구매 건 (부품을 사 옴. AUTO = 재고 부족 자동 요청). 부품 코드/이름은 요청 당시 스냅샷
CREATE TABLE purchase (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    part_id       BIGINT       NOT NULL,           -- 논리 참조: inventory_db.part.id
    part_code     VARCHAR(50)  NOT NULL,           -- 스냅샷
    part_name     VARCHAR(100) NOT NULL,           -- 스냅샷
    qty           INT          NOT NULL,
    supplier_id   BIGINT       NULL,               -- 논리 FK: supplier.id (자동 요청 때는 비어 있음)
    requested_by  BIGINT       NULL,               -- 논리 참조: auth_db.admin.id (자동 요청이면 비어 있음)
    status        VARCHAR(20)  NOT NULL,           -- REQUESTED, ORDERED, RECEIVED, CANCELLED
    source        VARCHAR(20)  NOT NULL,           -- MANUAL, AUTO
    ordered_at    DATETIME(6)  NULL,
    received_at   DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY idx_purchase_part_status (part_id, status),
    KEY idx_purchase_supplier (supplier_id),
    CONSTRAINT ck_purchase_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- processed_event: PK 를 id 로 통일, event_id 는 중복 불가
ALTER TABLE processed_event
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_processed_event_event_id (event_id);
