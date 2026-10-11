-- 사내 B2B 전환 (docs/specs/auth-account.md):
-- 고객 로그인/관리자 분리 모델(customer, admin)을 폐기하고,
-- 역할을 가진 사원 단일 계정(user_account)으로 통합한다.
-- 고객(대리점·법인)은 로그인하지 않으며 order 서비스의 거래처 데이터로만 존재한다.
DROP TABLE IF EXISTS customer;
DROP TABLE IF EXISTS admin;

CREATE TABLE user_account (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    employee_no  VARCHAR(20)  NOT NULL,             -- 사번 (로그인 ID)
    email        VARCHAR(100) NOT NULL,             -- 활성화 메일 발송·도메인 체크용
    password     VARCHAR(255) NULL,                 -- 비번 해시. 발급(INVITED) 직후엔 NULL, 활성화 때 설정
    name         VARCHAR(50)  NOT NULL,             -- 발급 시 관리자가 입력
    role         VARCHAR(20)  NOT NULL,             -- SALES, PURCHASE, PRODUCTION, WORKER, ADMIN
    status       VARCHAR(20)  NOT NULL,             -- INVITED, ACTIVE, DISABLED
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_employee_no (employee_no),
    UNIQUE KEY uk_user_account_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
