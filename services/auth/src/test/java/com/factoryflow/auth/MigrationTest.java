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
    void rejectsDuplicateEmployeeNo() {
        insertUser("E1001", "a@factoryflow.test");

        assertThatThrownBy(() -> insertUser("E1001", "b@factoryflow.test"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_user_account_employee_no");
    }

    @Test
    void rejectsDuplicateEmail() {
        insertUser("E2001", "dup@factoryflow.test");

        assertThatThrownBy(() -> insertUser("E2002", "dup@factoryflow.test"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_user_account_email");
    }

    @Test
    void allowsNullPasswordForInvitedAccount() {
        // 발급(INVITED) 직후엔 비번이 없다. 활성화 때 설정된다.
        insertUser("E3001", "invited@factoryflow.test");

        var password =
                jdbc.queryForObject(
                        "SELECT password FROM user_account WHERE employee_no = 'E3001'",
                        String.class);
        assertThat(password).isNull();
    }

    private void insertUser(String employeeNo, String email) {
        jdbc.update(
                "INSERT INTO user_account (public_id, employee_no, email, name, role, status,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, '홍길동', 'WORKER', 'INVITED', NOW(6), NOW(6))",
                UUID.randomUUID().toString(),
                employeeNo,
                email);
    }
}
