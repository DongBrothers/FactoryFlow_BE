-- 쓰지 않는 컬럼 정리 (docs/specs/unused-columns.md, #123)
-- 소프트 삭제는 사람이 지울 수 있는 기준 데이터(product, bom, equipment)에만 둔다
-- 생산 건의 시작·완료 시각은 작업지시(work_order)와 같은 값이라 작업지시에만 둔다
ALTER TABLE production DROP COLUMN started_at, DROP COLUMN completed_at, DROP COLUMN is_deleted;
ALTER TABLE work_order DROP COLUMN is_deleted;
ALTER TABLE work_order_step DROP COLUMN is_deleted;
-- 실적은 고치지 않으므로 다른 이력 테이블처럼 created_at 만 둔다
ALTER TABLE production_result DROP COLUMN updated_at, DROP COLUMN is_deleted;
