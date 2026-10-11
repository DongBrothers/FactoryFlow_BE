-- 고객 주문. status 는 품목 상태의 요약 (품목 상태가 바뀔 때 같은 트랜잭션에서 갱신)
CREATE TABLE customer_order (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id  BIGINT      NOT NULL,             -- 논리 참조: auth_db.customer.id
    status       VARCHAR(20) NOT NULL,             -- RECEIVED, WAITING_MATERIAL, IN_PRODUCTION, COMPLETED, CANCELLED, FAILED
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY idx_customer_order_customer (customer_id),
    KEY idx_customer_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 주문 품목. 제품 코드/이름은 주문 당시 스냅샷
CREATE TABLE order_item (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      BIGINT       NOT NULL,           -- 논리 FK: customer_order.id
    product_id    BIGINT       NOT NULL,           -- 논리 참조: production_db.product.id
    product_code  VARCHAR(50)  NOT NULL,           -- 스냅샷
    product_name  VARCHAR(100) NOT NULL,           -- 스냅샷
    qty           INT          NOT NULL,
    status        VARCHAR(20)  NOT NULL,           -- RECEIVED, WAITING_MATERIAL, IN_PRODUCTION, COMPLETED, CANCELLED, FAILED
    fail_reason   VARCHAR(255) NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_order_product (order_id, product_id),
    CONSTRAINT ck_order_item_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- processed_event: PK 를 id 로 통일, event_id 는 중복 불가
ALTER TABLE processed_event
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_processed_event_event_id (event_id);
