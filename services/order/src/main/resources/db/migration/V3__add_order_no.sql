-- 주문 번호: 고객에게 보여 주는 번호 (UUID). 내부 참조와 이벤트는 id 를 쓴다
-- 기존 주문이 있어도 실패하지 않도록 NULL 허용으로 추가 → 기존 행에 UUID 채우기 → NOT NULL, UNIQUE 순서로 바꾼다
ALTER TABLE customer_order
    ADD COLUMN order_no CHAR(36) NULL AFTER id;

UPDATE customer_order SET order_no = UUID() WHERE order_no IS NULL;

ALTER TABLE customer_order
    MODIFY COLUMN order_no CHAR(36) NOT NULL,
    ADD UNIQUE KEY uk_customer_order_order_no (order_no);
