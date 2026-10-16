-- 생산 완료 (docs/specs/production-completion.md)
-- 차량에 VIN 을 남긴다 (production.completed). 완료 전에는 NULL
ALTER TABLE order_vehicle ADD COLUMN vin VARCHAR(17) NULL AFTER production_id;

-- 품목·주문 요약을 여러 큐(status-changed, completed)가 동시에 바꿀 수 있어 낙관적 락을 건다
ALTER TABLE customer_order ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_item ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
