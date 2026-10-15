-- 차량(생산 건)별 상태 (docs/specs/order-production-status.md). 생산 건 = 차량 1대 (docs/planning/production-flow.md)
-- 품목 상태는 그 품목 차량들의 요약, 주문 상태는 품목들의 요약이다.
CREATE TABLE order_vehicle (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    order_id       BIGINT      NOT NULL,           -- 논리 FK: customer_order.id
    order_item_id  BIGINT      NOT NULL,           -- 논리 FK: order_item.id
    production_id  BIGINT      NOT NULL,           -- 논리 참조: production_db.production.id
    status         VARCHAR(20) NOT NULL,           -- RECEIVED, WAITING_MATERIAL, IN_PRODUCTION, COMPLETED, SHIPPED, CANCEL_REQUESTED, CANCELLED, FAILED
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_vehicle_production (production_id),
    KEY idx_order_vehicle_item (order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
