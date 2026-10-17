-- 라인 작업자 WORKER 를 생산 PRODUCTION 으로 합친다 (docs/specs/role-permissions.md, #118). 권한 차이가 출고뿐이었다
UPDATE user_account SET role = 'PRODUCTION', updated_at = NOW(6) WHERE role = 'WORKER';
