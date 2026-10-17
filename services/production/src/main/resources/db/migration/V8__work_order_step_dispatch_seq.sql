-- 배정 회차 번호 (docs/specs/simulator-restart.md). 공정을 맡길 때마다(로봇 배정, 작업자 시작) +1, 설비 보고의 회차가 다르면 무시한다
ALTER TABLE work_order_step
    ADD COLUMN dispatch_seq INT NOT NULL DEFAULT 0 AFTER dispatched_at;
