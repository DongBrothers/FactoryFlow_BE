package com.factoryflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.test.db.MigrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationTest extends MigrationTestSupport {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = migrateTo("1");
    }

    @Test
    void createsSeparateCustomerAndAdminTables() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        assertThat(tables).contains("customer", "admin");
    }

    @Test
    void rejectsDuplicateCustomerEmail() {
        insertCustomer("dup@factoryflow.test");

        assertThatThrownBy(() -> insertCustomer("dup@factoryflow.test"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_customer_email");
    }

    @Test
    void rejectsDuplicateAdminEmail() {
        insertAdmin("admin-dup@factoryflow.test");

        assertThatThrownBy(() -> insertAdmin("admin-dup@factoryflow.test"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_admin_email");
    }

    @Test
    void adminRequiresDepartment() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO admin (email, password, name, position,"
                                                + " created_at, updated_at)"
                                                + " VALUES ('no-dept@factoryflow.test', 'hash',"
                                                + " '관리자', '과장', NOW(6), NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("department");
    }

    @Test
    void adminRequiresPosition() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO admin (email, password, name, department,"
                                                + " created_at, updated_at)"
                                                + " VALUES ('no-position@factoryflow.test', 'hash',"
                                                + " '관리자', '구매팀', NOW(6), NOW(6))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("position");
    }

    private void insertCustomer(String email) {
        jdbc.update(
                "INSERT INTO customer (email, password, name, created_at, updated_at)"
                        + " VALUES (?, 'hash', '고객', NOW(6), NOW(6))",
                email);
    }

    private void insertAdmin(String email) {
        jdbc.update(
                "INSERT INTO admin (email, password, name, department, position, created_at,"
                        + " updated_at) VALUES (?, 'hash', '관리자', '구매팀', '과장', NOW(6), NOW(6))",
                email);
    }
}
