-- 배정 시각 (docs/specs/simulator.md). 배정 뒤 시작 보고가 오지 않으면(설비 재시작, 통신 끊김) 시간 초과로 되돌린다
ALTER TABLE work_order_step
    ADD COLUMN dispatched_at DATETIME(6) NULL AFTER equipment_code,
    ADD KEY idx_work_order_step_dispatched (status, dispatched_at);
