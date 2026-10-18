-- 쓰지 않는 테이블·컬럼 정리 (docs/specs/unused-columns.md, #123)
-- 고객은 엔티티·API 가 없고 order 의 customer_id 도 이 테이블을 보지 않는다. 필요해지면 order 에 둔다
DROP TABLE customer;
-- 계정을 막는 것은 status DISABLED 다. 계정을 지우지 않는다
ALTER TABLE user_account DROP COLUMN is_deleted;
