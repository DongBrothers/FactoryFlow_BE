package com.factoryflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.test.db.MigrationTestSupport;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationTest extends MigrationTestSupport {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        // V3 가 이미 있던 WORKER 계정을 PRODUCTION 으로 바꾸는지 보려고, V2 까지 올려 한 명 넣은 뒤 끝까지 적용한다
        var v2 =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(v2).target("2").load().migrate();
        new JdbcTemplate(v2)
                .update(
                        "INSERT INTO user_account (public_id, email, password, name, role, status,"
                                + " created_at, updated_at) VALUES (?, 'worker@gmail.com', 'hash',"
                                + " '작업자', 'WORKER', 'ACTIVE', NOW(6), NOW(6))",
                        UUID.randomUUID().toString());
        jdbc = migrateTo("5");
    }

    @Test
    void v3MergesWorkerIntoProduction() {
        // V3 가 PRODUCTION 으로 바꾼 역할을 V5 가 role_id 로 옮긴다
        assertThat(
                        jdbc.queryForObject(
                                "SELECT r.code FROM user_account u JOIN role r ON r.id = u.role_id"
                                        + " WHERE u.email = 'worker@gmail.com'",
                                String.class))
                .isEqualTo("PRODUCTION");
    }

    @Test
    void v5ReplacesRoleColumnWithRoleId() {
        assertThat(columnCount(jdbc, "user_account", "role")).isZero();
        assertThat(columnCount(jdbc, "user_account", "role_id")).isEqualTo(1);
    }

    @Test
    void createsUserAccountAndDropsAdminAndCustomer() {
        var tables = jdbc.queryForList("SHOW TABLES", String.class);

        // admin 은 user_account 로 대체되어 사라지고, 쓰는 곳이 없던 customer 는 V4 에서 지운다 (#123).
        assertThat(tables).contains("user_account");
        assertThat(tables).doesNotContain("admin", "customer");
    }

    @Test
    void customerHadNoPasswordColumnUntilV4DropsIt() {
        // 고객은 로그인하지 않으므로 V2 가 비밀번호 컬럼을 지웠다. 테이블 자체는 V4 가 지운다.
        var root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE auth_v3");
        var upgrade =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/auth_v3"),
                        "root",
                        mysql.getPassword());
        Flyway.configure().dataSource(upgrade).target("3").load().migrate();
        var db = new JdbcTemplate(upgrade);
        assertThat(columnCount(db, "customer", "password")).isZero();
        assertThat(columnCount(db, "customer", "email")).isEqualTo(1);

        Flyway.configure().dataSource(upgrade).target("4").load().migrate();

        assertThat(db.queryForList("SHOW TABLES", String.class)).doesNotContain("customer");
    }

    @Test
    void v4DropsUserAccountSoftDelete() {
        // 계정을 막는 것은 status DISABLED 다. 지우지 않으므로 is_deleted 를 두지 않는다 (#123).
        assertThat(columnCount(jdbc, "user_account", "is_deleted")).isZero();
        assertThat(columnCount(jdbc, "user_account", "status")).isEqualTo(1);
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
    void v5SeedsFourRoles() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT CONCAT(code, ':', name) FROM role ORDER BY sort_order",
                                String.class))
                .containsExactly("SALES:영업", "PURCHASE:구매", "PRODUCTION:생산", "ADMIN:관리자");
    }

    @Test
    void rejectsUserWithUnknownRole() {
        // user_account.role_id 는 role.id 를 가리킨다 (V5)
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO user_account (public_id, email, password, name,"
                                                + " role_id, status, created_at, updated_at) VALUES (?,"
                                                + " 'nope@factoryflow.test', 'hash', '홍길동', 999999,"
                                                + " 'ACTIVE', NOW(6), NOW(6))",
                                        UUID.randomUUID().toString()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_user_account_role");
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

    private static int columnCount(JdbcTemplate db, String table, String column) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND column_name = ?",
                Integer.class,
                table,
                column);
    }

    private void insertUser(String email, String password) {
        jdbc.update(
                "INSERT INTO user_account (public_id, email, password, name, role_id, status,"
                        + " created_at, updated_at)"
                        + " SELECT ?, ?, ?, '홍길동', id, 'ACTIVE', NOW(6), NOW(6)"
                        + " FROM role WHERE code = 'PRODUCTION'",
                UUID.randomUUID().toString(),
                email,
                password);
    }
}
