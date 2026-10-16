-- 구매·입고 (docs/specs/purchase-receiving.md)
-- 부족 합치기(inventory.shortage 수신)와 발주·입고(구매 담당 API)가 같은 구매 건을 동시에 바꾸지 않게 하는 낙관적 락
ALTER TABLE purchase ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 공급업체 기본 데이터. 발주 때 고른다 (등록·수정 API 는 이후)
INSERT INTO supplier (name, contact, created_at, updated_at) VALUES
    ('현대모비스', '02-0000-0001', NOW(6), NOW(6)),
    ('한국타이어', '02-0000-0002', NOW(6), NOW(6)),
    ('현대트랜시스', '02-0000-0003', NOW(6), NOW(6));
