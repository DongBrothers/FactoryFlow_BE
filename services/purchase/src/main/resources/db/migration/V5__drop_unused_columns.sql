-- 쓰지 않는 컬럼 정리 (docs/specs/unused-columns.md, #123)
-- requested_by 는 V2 에서 지운 admin 을 가리켰다 (발주한 직원은 ordered_by).
-- source 는 자동 구매를 없앤 뒤(#114) 항상 MANUAL 이다. 옛 자동 요청은 status CANCELLED 로 끝나 있다
-- 구매 건은 지우지 않으므로 소프트 삭제도 뺀다 (기준 데이터인 supplier 에만 둔다)
ALTER TABLE purchase DROP COLUMN requested_by, DROP COLUMN source, DROP COLUMN is_deleted;
