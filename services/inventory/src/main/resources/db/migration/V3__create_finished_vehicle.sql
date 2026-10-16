-- 완성차 재고 (docs/specs/production-completion.md). production.completed 를 받을 때 차 한 대마다 한 줄. 출고(#77)하면 SHIPPED
CREATE TABLE finished_vehicle (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    vin            VARCHAR(17) NOT NULL,
    production_id  BIGINT      NOT NULL,           -- 논리 참조: production_db.production.id
    order_id       BIGINT      NOT NULL,           -- 논리 참조: order_db.customer_order.id
    product_id     BIGINT      NOT NULL,           -- 논리 참조: production_db.product.id
    status         VARCHAR(20) NOT NULL,           -- IN_STOCK, SHIPPED
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_finished_vehicle_vin (vin),
    UNIQUE KEY uk_finished_vehicle_production (production_id),
    KEY idx_finished_vehicle_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
