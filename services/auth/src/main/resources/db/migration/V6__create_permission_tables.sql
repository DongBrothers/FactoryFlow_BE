-- 역할별 권한을 gateway 코드에서 DB 로 뺀다 (docs/specs/role-permissions.md, auth-account.md 결정 기록 17).
-- 로그인 때 이 표를 읽어 JWT permissions 클레임에 넣고, gateway 는 역할 이름 없이 권한 코드만 본다
CREATE TABLE permission (
    id    BIGINT      NOT NULL AUTO_INCREMENT,
    code  VARCHAR(30) NOT NULL,             -- JWT permissions 클레임·gateway 검사에 쓰는 값
    name  VARCHAR(30) NOT NULL,             -- 화면에 보이는 이름
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO permission (code, name) VALUES
    ('READ', '조회'),
    ('ORDER_WRITE', '주문 쓰기'),
    ('PRODUCTION_WRITE', '생산 쓰기'),
    ('PURCHASE_WRITE', '구매 쓰기'),
    ('ACCOUNT_ADMIN', '계정 관리');

CREATE TABLE role_permission (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    role_id        BIGINT NOT NULL,
    permission_id  BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 처음 값은 지금 gateway 표와 같은 동작이다. 이후 역할·권한 변경은 새 마이그레이션으로 한다
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON (r.code, p.code) IN (
    ('SALES', 'READ'), ('SALES', 'ORDER_WRITE'),
    ('PURCHASE', 'READ'), ('PURCHASE', 'PURCHASE_WRITE'),
    ('PRODUCTION', 'READ'), ('PRODUCTION', 'PRODUCTION_WRITE'),
    ('ADMIN', 'ACCOUNT_ADMIN'));
