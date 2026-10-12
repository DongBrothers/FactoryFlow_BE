-- 사내 B2B 전환 (docs/specs/auth-account.md):
-- 관리자 계정(admin)을 역할 기반 직원 계정(user_account)으로 대체한다.
-- 발급 대기 계정은 Redis 에만 두고, 활성화가 끝난 계정만 여기 저장한다.
-- 고객(customer)은 로그인하지 않으므로 비밀번호 컬럼을 제거하고 데이터로만 보관한다.
-- (고객 정보의 제자리는 order 서비스지만, order 구현 전까지 auth_db 에 둔다.)
DROP TABLE IF EXISTS admin;

ALTER TABLE customer DROP COLUMN password;

CREATE TABLE user_account (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    public_id    VARCHAR(36)  NOT NULL,             -- 외부·토큰(sub) 식별용 UUID. 내부 참조는 id
    email        VARCHAR(100) NOT NULL,             -- 로그인 ID. 소문자로 정규화해 저장
    password     VARCHAR(255) NOT NULL,             -- 비번 해시. 활성화가 끝난 계정만 저장되므로 항상 있다
    name         VARCHAR(50)  NOT NULL,             -- 발급 시 관리자가 입력
    role         VARCHAR(20)  NOT NULL,             -- SALES, PURCHASE, PRODUCTION, WORKER, ADMIN
    status       VARCHAR(20)  NOT NULL,             -- ACTIVE, DISABLED
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_public_id (public_id),
    UNIQUE KEY uk_user_account_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
