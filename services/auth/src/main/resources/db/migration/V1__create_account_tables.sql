-- 고객 (회원가입으로 생성)
CREATE TABLE customer (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    email       VARCHAR(100) NOT NULL,
    password    VARCHAR(255) NOT NULL,             -- 해시값만 저장
    name        VARCHAR(50)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 관리자 (회원가입 없이 별도 절차로만 생성)
CREATE TABLE admin (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    email       VARCHAR(100) NOT NULL,
    password    VARCHAR(255) NOT NULL,             -- 해시값만 저장
    name        VARCHAR(50)  NOT NULL,
    department  VARCHAR(50)  NOT NULL,             -- 부서
    position    VARCHAR(50)  NOT NULL,             -- 직책
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
