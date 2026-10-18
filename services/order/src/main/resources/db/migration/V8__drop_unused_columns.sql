-- 쓰지 않는 컬럼 정리 (docs/specs/unused-columns.md, #123)
-- fail_reason 은 FAILED 상태가 있던 때의 컬럼이다. 주문 데이터는 지우지 않으므로 소프트 삭제도 뺀다
ALTER TABLE customer_order DROP COLUMN is_deleted;
ALTER TABLE order_item DROP COLUMN fail_reason, DROP COLUMN is_deleted;
ALTER TABLE order_vehicle DROP COLUMN is_deleted;
