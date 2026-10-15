-- 생산 건 = 차량 1대 (docs/planning/production-flow.md). 수량 대신 주문·제품 안의 차량 번호(seq)를 둔다.
-- 기존 행(dev)은 차량 1대(seq 1)로 남는다. 운영 데이터는 없다.
ALTER TABLE production
    DROP INDEX uk_production_order_product,
    DROP CHECK ck_production_qty,
    DROP COLUMN qty,
    ADD COLUMN seq INT NOT NULL DEFAULT 1 AFTER product_id,
    ADD UNIQUE KEY uk_production_order_product_seq (order_id, product_id, seq),
    ADD CONSTRAINT ck_production_seq CHECK (seq > 0);
