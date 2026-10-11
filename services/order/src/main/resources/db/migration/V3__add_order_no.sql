-- 주문 번호: 고객에게 보여 주는 번호 (UUID). 내부 참조와 이벤트는 id 를 쓴다
ALTER TABLE customer_order
    ADD COLUMN order_no CHAR(36) NOT NULL AFTER id,
    ADD UNIQUE KEY uk_customer_order_order_no (order_no);
