package com.factoryflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.test.db.MigrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationTest extends MigrationTestSupport {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = migrateTo("2");
    }

    @Test
    void createsUserAccountAndDropsAdminButKeepsCustomer() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        // admin 은 user_account 로 대체되어 사라지고, customer 는 데이터 테이블로 남는다.
        assertThat(tables).contains("user_account", "customer");
        assertThat(tables).doesNotContain("admin");
    }

    @Test
    void customerHasNoPasswordColumn() {
        // 고객은 로그인하지 않으므로 비밀번호 컬럼이 제거되어 있다.
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE()"
                                + " AND table_name = 'customer' AND column_name = 'password'",
                        Integer.class);

        assertThat(count).isZero();
    }

    @Test
    void userAccountHasNoEmployeeNoColumn() {
        // 로그인 ID 는 이메일이다. 사번은 두지 않는다.
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE()"
                                + " AND table_name = 'user_account' AND column_name = 'employee_no'",
                        Integer.class);

        assertThat(count).isZero();
    }

    @Test
    void rejectsDuplicateEmail() {
        insertUser("dup@factoryflow.test", "hash");

        assertThatThrownBy(() -> insertUser("dup@factoryflow.test", "hash"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_user_account_email");
    }

    @Test
    void rejectsNullPassword() {
        // 활성화(비번 설정)가 끝난 계정만 저장되므로 비번은 항상 있다.
        assertThatThrownBy(() -> insertUser("nopw@factoryflow.test", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("password");
    }

    private void insertUser(String email, String password) {
        jdbc.update(
                "INSERT INTO user_account (public_id, email, password, name, role, status,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, '홍길동', 'WORKER', 'ACTIVE', NOW(6), NOW(6))",
                UUID.randomUUID().toString(),
                email,
                password);
    }
}
