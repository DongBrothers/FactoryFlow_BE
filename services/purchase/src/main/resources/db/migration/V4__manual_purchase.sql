-- 구매 담당이 직접 구매 (docs/specs/purchase-manual.md). 자동 구매(inventory.shortage)와 요청 단계를 없앤다
-- 발주한 직원 public_id (gateway 의 X-User-Id)
ALTER TABLE purchase ADD COLUMN ordered_by VARCHAR(36) NULL AFTER requested_by;
-- 발주되지 않은 자동 요청은 발주할 API 가 없어지므로 취소로 끝낸다
UPDATE purchase SET status = 'CANCELLED', updated_at = NOW(6) WHERE status = 'REQUESTED';
