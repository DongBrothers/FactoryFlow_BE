-- 완성차 출고 (docs/specs/shipment.md). 출고한 때와, 같은 차를 동시에 출고하지 못하게 막는 version
ALTER TABLE finished_vehicle
    ADD COLUMN shipped_at DATETIME(6) NULL AFTER status,
    ADD COLUMN version    BIGINT      NOT NULL DEFAULT 0 AFTER shipped_at;
