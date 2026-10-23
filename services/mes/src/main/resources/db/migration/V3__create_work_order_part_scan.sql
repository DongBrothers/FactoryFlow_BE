-- 의장 공정에서 작업자가 찍은 부품 LOT (docs/specs/part-lot-scan.md, #170). 차가 완성되면 mes.vehicle-completed 에 실려
-- inventory 가 그 LOT 으로 재고를 뺀다. 의장 재작업 뒤 다시 양품이면 마지막에 찍은 것으로 바꾼다
CREATE TABLE work_order_part_scan (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    work_order_id  BIGINT      NOT NULL,             -- 논리 참조: work_order.id
    part_id        BIGINT      NOT NULL,             -- 논리 참조: inventory 의 부품 id
    lot_no         VARCHAR(32) NOT NULL,
    scanned_by     VARCHAR(36) NOT NULL,             -- 찍은 직원 public_id (X-User-Id)
    scanned_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_part_scan (work_order_id, part_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
