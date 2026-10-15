-- MES 작업지시·공정·실적과 설비 (docs/specs/production-work-order.md)

-- 같은 생산 건을 동시에 바꾸는 것(공정 시작 vs 취소 수신 등)을 막는 낙관적 락
ALTER TABLE production ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 설비: 공정마다 여러 대. 상태는 simulator 의 equipment.status-changed 로 바뀐다 (#74)
CREATE TABLE equipment (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30) NOT NULL,              -- simulator 와 같은 코드 (예: PRESS-01)
    name        VARCHAR(100) NOT NULL,
    process     VARCHAR(20) NOT NULL,              -- PRESS, BODY, PAINT, ASSEMBLY, INSPECTION
    status      VARCHAR(20) NOT NULL,              -- RUNNING, STOPPED, FAULT
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_code (code),
    KEY idx_equipment_process (process)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO equipment (code, name, process, status, created_at, updated_at) VALUES
    ('PRESS-01', '프레스기 1호', 'PRESS', 'RUNNING', NOW(6), NOW(6)),
    ('PRESS-02', '프레스기 2호', 'PRESS', 'RUNNING', NOW(6), NOW(6)),
    ('BODY-01', '용접 로봇 1호', 'BODY', 'RUNNING', NOW(6), NOW(6)),
    ('BODY-02', '용접 로봇 2호', 'BODY', 'RUNNING', NOW(6), NOW(6)),
    ('PAINT-01', '도장 로봇 1호', 'PAINT', 'RUNNING', NOW(6), NOW(6)),
    ('PAINT-02', '도장 로봇 2호', 'PAINT', 'RUNNING', NOW(6), NOW(6)),
    ('ASSEMBLY-01', '의장 라인 1호', 'ASSEMBLY', 'RUNNING', NOW(6), NOW(6)),
    ('ASSEMBLY-02', '의장 라인 2호', 'ASSEMBLY', 'RUNNING', NOW(6), NOW(6)),
    ('INSPECTION-01', '검사 라인 1호', 'INSPECTION', 'RUNNING', NOW(6), NOW(6)),
    ('INSPECTION-02', '검사 라인 2호', 'INSPECTION', 'RUNNING', NOW(6), NOW(6));

-- 작업지시: 생산 건(차량 1대)당 1건. inventory.reserved 로 생산 건이 READY 가 될 때 만든다
CREATE TABLE work_order (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    production_id  BIGINT      NOT NULL,           -- 논리 FK: production.id
    order_id       BIGINT      NOT NULL,           -- 논리 참조: order_db.customer_order.id
    product_id     BIGINT      NOT NULL,           -- 논리 FK: product.id
    status         VARCHAR(20) NOT NULL,           -- RELEASED, IN_PROGRESS, COMPLETED, CANCELLED, FAILED
    vin            VARCHAR(17) NULL,               -- 검사 완료 때 발행 (#73)
    released_at    DATETIME(6) NOT NULL,
    started_at     DATETIME(6) NULL,
    completed_at   DATETIME(6) NULL,
    step_changed_at DATETIME(6) NULL,              -- 공정이 마지막으로 바뀐 시각. 공정이 바뀔 때마다 작업지시 version 도 오르게 한다
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_production (production_id),
    KEY idx_work_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 공정: 작업지시마다 라우팅 순서대로 5개
CREATE TABLE work_order_step (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    work_order_id   BIGINT      NOT NULL,          -- 논리 FK: work_order.id
    process         VARCHAR(20) NOT NULL,
    seq             INT         NOT NULL,          -- 라우팅 순서 1~5
    status          VARCHAR(20) NOT NULL,          -- PENDING, DISPATCHED(로봇 공정 배정), IN_PROGRESS, DONE
    equipment_code  VARCHAR(30) NULL,              -- 배정되었거나 진행 중인 설비
    started_at      DATETIME(6) NULL,
    ended_at        DATETIME(6) NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_step_process (work_order_id, process),
    KEY idx_work_order_step_dispatch (process, status),
    KEY idx_work_order_step_equipment (equipment_code, status),
    CONSTRAINT ck_work_order_step_seq CHECK (seq BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 실적: 공정을 끝낼 때마다 한 줄 (양품 또는 불량)
CREATE TABLE production_result (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    work_order_step_id  BIGINT       NOT NULL,     -- 논리 FK: work_order_step.id
    result              VARCHAR(10)  NOT NULL,     -- GOOD, DEFECT
    defect_reason       VARCHAR(255) NULL,
    worker_id           VARCHAR(36)  NULL,         -- 작업자 public_id (gateway 의 X-User-Id). 설비 보고(로봇 공정)면 NULL
    equipment_code      VARCHAR(30)  NOT NULL,
    started_at          DATETIME(6)  NOT NULL,
    ended_at            DATETIME(6)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY idx_production_result_step (work_order_step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
