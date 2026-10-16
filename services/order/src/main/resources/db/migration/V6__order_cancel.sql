-- 주문 취소 (docs/specs/order-cancel.md)
-- 진행 중인 취소 요청 회차의 시각. 답이 다 오면 비운다. 이 시각 뒤에 취소된 차량이 있으면 order.cancelled (회차당 한 번)
ALTER TABLE customer_order ADD COLUMN cancel_requested_at DATETIME(6) NULL;
-- 차량이 취소된 시각 (production.cancelled)
ALTER TABLE order_vehicle ADD COLUMN cancelled_at DATETIME(6) NULL;
