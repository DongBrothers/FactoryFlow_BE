-- 주문 쪽 상태에서 부품 대기(WAITING_MATERIAL)를 없앤다 (docs/specs/order-production-status.md, #117)
-- 부품을 기다리는 차도 고객에게는 접수(RECEIVED)로 보인다. production 의 생산 건 상태는 그대로다
UPDATE order_vehicle SET status = 'RECEIVED', updated_at = NOW(6) WHERE status = 'WAITING_MATERIAL';
UPDATE order_item SET status = 'RECEIVED', updated_at = NOW(6) WHERE status = 'WAITING_MATERIAL';
UPDATE customer_order SET status = 'RECEIVED', updated_at = NOW(6) WHERE status = 'WAITING_MATERIAL';
