-- 설비 상태 이력 (docs/specs/equipment-status.md). equipment.status-changed 로 상태가 바뀔 때마다 한 줄. 추가만 한다 (OEE 데이터)
CREATE TABLE equipment_status_history (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    equipment_code  VARCHAR(30) NOT NULL,          -- 논리 FK: equipment.code
    from_status     VARCHAR(20) NOT NULL,          -- RUNNING, STOPPED, FAULT
    to_status       VARCHAR(20) NOT NULL,
    changed_at      DATETIME(6) NOT NULL,          -- 설비 상태가 바뀐 시각 (이벤트 발생 시각)
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_equipment_status_history_equipment (equipment_code, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
