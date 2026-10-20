-- 완성차 보관 위치 (docs/specs/finished-vehicle-location.md, #138)
-- 출고장 하나, 구역 A·B, 구역마다 20자리. 완성차가 들어오면 빈 자리 중 코드 순 첫 자리에 세우고, 출고하면 비운다
CREATE TABLE storage_slot (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    code                 VARCHAR(10) NOT NULL,         -- 예: A-01
    zone                 VARCHAR(10) NOT NULL,         -- A, B
    finished_vehicle_id  BIGINT      NULL,             -- 논리 FK: finished_vehicle.id. 비었으면 NULL
    version              BIGINT      NOT NULL DEFAULT 0, -- 두 차가 같은 빈 자리를 동시에 고르면 늦은 쪽이 실패한다
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_storage_slot_code (code),
    UNIQUE KEY uk_storage_slot_vehicle (finished_vehicle_id) -- 한 차는 한 자리에만
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO storage_slot (code, zone, created_at, updated_at)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 20)
SELECT CONCAT(z.zone, '-', LPAD(n.i, 2, '0')), z.zone, NOW(6), NOW(6)
FROM (SELECT 'A' AS zone UNION ALL SELECT 'B') z CROSS JOIN n
ORDER BY z.zone, n.i;

-- 완성차가 선 자리. 자리 대기면 NULL. 출고한 뒤에도 어디서 나갔는지 남긴다
ALTER TABLE finished_vehicle ADD COLUMN slot_code VARCHAR(10) NULL AFTER status;

-- 이미 창고에 있는 차는 완성차 id 순으로 자리를 채운다 (40대를 넘으면 나머지는 자리 대기)
UPDATE finished_vehicle f
JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM finished_vehicle WHERE status = 'IN_STOCK') v ON v.id = f.id
JOIN (SELECT code, ROW_NUMBER() OVER (ORDER BY code) AS rn FROM storage_slot) s ON s.rn = v.rn
SET f.slot_code = s.code;

UPDATE storage_slot s
JOIN finished_vehicle f ON f.slot_code = s.code AND f.status = 'IN_STOCK'
SET s.finished_vehicle_id = f.id;
