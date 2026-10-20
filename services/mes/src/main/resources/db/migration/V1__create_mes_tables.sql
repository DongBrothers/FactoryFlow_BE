-- mes 서비스 테이블 (docs/specs/mes-split.md, #134). production_db 의 MES 테이블(production V4~V9)의 최종 모양 그대로 옮긴다
-- 서비스끼리는 DB 를 공유하지 않는다: 생산 건(production)·제품(product)은 id 로만 가리킨다 (논리 참조)

-- 이벤트 발행 (common-event EventPublisher, OutboxRelay)
CREATE TABLE outbox (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    event_id     CHAR(36)     NOT NULL,
    event_name   VARCHAR(100) NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_unpublished (published_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 받은 이벤트 중복 처리 방지 (common-event ProcessedEventGuard)
CREATE TABLE processed_event (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_processed_event_event_id (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 설비: 공정마다 여러 대. 상태는 simulator 의 equipment.status-changed 로 바뀐다
CREATE TABLE equipment (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    code             VARCHAR(30)  NOT NULL,         -- simulator 와 같은 코드 (예: PRESS-01)
    name             VARCHAR(100) NOT NULL,
    process          VARCHAR(20)  NOT NULL,         -- PRESS, BODY, PAINT, ASSEMBLY, INSPECTION
    status           VARCHAR(20)  NOT NULL,         -- RUNNING, STOPPED, FAULT
    occupied_step_id BIGINT       NULL,             -- 지금 맡은 공정 (work_order_step.id). 비었으면 NULL. 같은 설비를 두 차가 잡지 않게 version 과 함께 쓴다
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    is_deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_code (code),
    KEY idx_equipment_process (process)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 설비 상태 이력: equipment.status-changed 로 상태가 바뀔 때마다 한 줄. 추가만 한다 (OEE 데이터)
CREATE TABLE equipment_status_history (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    equipment_code  VARCHAR(30) NOT NULL,           -- 논리 FK: equipment.code
    from_status     VARCHAR(20) NOT NULL,           -- RUNNING, STOPPED, FAULT
    to_status       VARCHAR(20) NOT NULL,
    changed_at      DATETIME(6) NOT NULL,           -- 설비 상태가 바뀐 시각 (이벤트 발생 시각)
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_equipment_status_history_equipment (equipment_code, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 작업지시: 생산 건(차량 1대)당 1건. production.released 를 받아 만든다. 발행보다 먼저 온 취소 요청이면 CANCELLED 로 만든다
CREATE TABLE work_order (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    production_id   BIGINT      NOT NULL,           -- 논리 참조: production_db.production.id
    order_id        BIGINT      NOT NULL,           -- 논리 참조: order_db.customer_order.id
    product_id      BIGINT      NOT NULL,           -- 논리 참조: production_db.product.id (발행 전 취소면 0)
    status          VARCHAR(20) NOT NULL,           -- RELEASED, IN_PROGRESS, COMPLETED, CANCELLED
    vin             VARCHAR(17) NULL,               -- 검사 완료 때 발행
    released_at     DATETIME(6) NOT NULL,
    started_at      DATETIME(6) NULL,
    completed_at    DATETIME(6) NULL,
    step_changed_at DATETIME(6) NULL,               -- 공정이 마지막으로 바뀐 시각. 공정이 바뀔 때마다 작업지시 version 도 오르게 한다
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_production (production_id),
    UNIQUE KEY uk_work_order_vin (vin),             -- VIN 은 차 한 대마다 하나 (작업지시 id 가 일련번호라 겹치지 않지만 DB 로도 막는다)
    KEY idx_work_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 공정: 작업지시마다 라우팅 순서대로 5개
CREATE TABLE work_order_step (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    work_order_id   BIGINT      NOT NULL,           -- 논리 FK: work_order.id
    process         VARCHAR(20) NOT NULL,
    seq             INT         NOT NULL,           -- 라우팅 순서 1~5
    status          VARCHAR(20) NOT NULL,           -- PENDING, DISPATCHED(로봇 공정 배정), IN_PROGRESS, DONE
    equipment_code  VARCHAR(30) NULL,               -- 배정되었거나 진행 중인 설비
    dispatched_at   DATETIME(6) NULL,               -- 배정 시각. 시작 보고가 오지 않으면 시간 초과로 되돌린다
    dispatch_seq    INT         NOT NULL DEFAULT 0, -- 배정 회차. 공정을 맡길 때마다 +1, 설비 보고의 회차가 다르면 무시한다
    started_at      DATETIME(6) NULL,
    ended_at        DATETIME(6) NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_step_process (work_order_id, process),
    KEY idx_work_order_step_dispatch (process, status),
    KEY idx_work_order_step_equipment (equipment_code, status),
    KEY idx_work_order_step_dispatched (status, dispatched_at),
    CONSTRAINT ck_work_order_step_seq CHECK (seq BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 실적: 공정을 끝낼 때마다 한 줄 (양품 또는 불량). 고치지 않으므로 created_at 만 둔다
CREATE TABLE production_result (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    work_order_step_id  BIGINT       NOT NULL,      -- 논리 FK: work_order_step.id
    result              VARCHAR(10)  NOT NULL,      -- GOOD, DEFECT
    defect_reason       VARCHAR(255) NULL,
    worker_id           VARCHAR(36)  NULL,          -- 작업자 public_id (gateway 의 X-User-Id). 설비 보고(로봇 공정)면 NULL
    equipment_code      VARCHAR(30)  NOT NULL,
    started_at          DATETIME(6)  NOT NULL,
    ended_at            DATETIME(6)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_production_result_step (work_order_step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
