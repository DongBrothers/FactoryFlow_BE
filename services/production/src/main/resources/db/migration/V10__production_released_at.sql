-- 작업지시 발행 시각 (docs/specs/mes-split.md, #134). 작업지시는 mes 가 만들고, production 은 발행했는지만 안다
-- 발행 대기 목록(READY 이고 released_at NULL)과 취소 판단(발행 뒤면 mes 에 묻는다)에 쓴다
ALTER TABLE production ADD COLUMN released_at DATETIME(6) NULL AFTER status;

-- 이미 작업지시가 있는 생산 건은 그 발행 시각으로 채운다. MES 테이블은 mes 로 옮긴 뒤 다음 마이그레이션에서 지운다
UPDATE production p JOIN work_order w ON w.production_id = p.id SET p.released_at = w.released_at;
