-- 부품 + 재고. 창고 실물 = available + reserved
CREATE TABLE part (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    unit        VARCHAR(20)  NOT NULL,             -- EA, SET, KG
    available   INT          NOT NULL DEFAULT 0,   -- 바로 쓸 수 있는 수량
    reserved    INT          NOT NULL DEFAULT 0,   -- 생산에 잡혀 있는 수량 (part_reservation 과 같은 트랜잭션에서 갱신)
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_part_code (code),
    CONSTRAINT ck_part_available CHECK (available >= 0),
    CONSTRAINT ck_part_reserved CHECK (reserved >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 부품 예약 (생산 건이 쓸 부품). 부족하면 WAITING 으로 대기, id 순서가 대기 순서
CREATE TABLE part_reservation (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    production_id  BIGINT      NOT NULL,           -- 논리 참조: production_db.production.id
    part_id        BIGINT      NOT NULL,           -- 논리 FK: part.id
    qty            INT         NOT NULL,
    status         VARCHAR(20) NOT NULL,           -- WAITING, RESERVED, RELEASED, CONSUMED
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_part_reservation_production_part (production_id, part_id),
    KEY idx_part_reservation_status (status, id),
    KEY idx_part_reservation_part (part_id),
    CONSTRAINT ck_part_reservation_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 재고 이력 (추가만 하고 수정하지 않음). 증감 방향은 type 으로 정한다
CREATE TABLE stock_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    part_id     BIGINT      NOT NULL,              -- 논리 FK: part.id
    qty         INT         NOT NULL,
    type        VARCHAR(20) NOT NULL,              -- RESERVE, RELEASE, RECEIVE, CONSUME
    ref_type    VARCHAR(20) NOT NULL,              -- PRODUCTION, PURCHASE
    ref_id      BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_stock_history_part (part_id, created_at),
    KEY idx_stock_history_ref (ref_type, ref_id),
    CONSTRAINT ck_stock_history_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- processed_event: PK 를 id 로 통일, event_id 는 중복 불가
ALTER TABLE processed_event
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_processed_event_event_id (event_id);
