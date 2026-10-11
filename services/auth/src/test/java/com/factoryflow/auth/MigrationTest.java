package com.factoryflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MigrationTest {

    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var dataSource =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var result = Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("2");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsUserAccountAndDropsPlaceholderTables() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables).contains("user_account");
        assertThat(tables).doesNotContain("customer", "admin");
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
                "INSERT INTO user_account (employee_no, email, name, role, status, created_at,"
                        + " updated_at) VALUES (?, ?, '홍길동', 'WORKER', 'INVITED', NOW(6), NOW(6))",
                employeeNo,
                email);
    }
}
