-- 역할을 코드 enum 에서 테이블로 뺀다 (docs/specs/auth-account.md 결정 기록 3). 계정 발급 화면의 선택지가 이 표에서 나온다
CREATE TABLE role (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    code        VARCHAR(20) NOT NULL,             -- JWT role 클레임·gateway 권한 검사·API 에 쓰는 값
    name        VARCHAR(20) NOT NULL,             -- 화면에 보이는 이름
    sort_order  INT         NOT NULL,             -- 선택지 순서
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO role (code, name, sort_order) VALUES
    ('SALES', '영업', 1),
    ('PURCHASE', '구매', 2),
    ('PRODUCTION', '생산', 3),
    ('ADMIN', '관리자', 4);

-- 계정의 역할 코드 글자를 role_id 로 옮긴다. 표에 없는 코드가 남아 있으면 NOT NULL 에서 실패해 멈춘다
ALTER TABLE user_account ADD COLUMN role_id BIGINT NULL AFTER name;

UPDATE user_account u JOIN role r ON r.code = u.role SET u.role_id = r.id;

ALTER TABLE user_account
    MODIFY role_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_user_account_role FOREIGN KEY (role_id) REFERENCES role (id),
    DROP COLUMN role;
