package com.factoryflow.test.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.test.container.TestContainersConfig;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 서비스의 Flyway 마이그레이션을 빈 MySQL 에 적용해 보는 테스트의 공통 부분. 스프링 컨텍스트 없이 마이그레이션만 돌린다.
 *
 * <pre>
 * class MigrationTest extends MigrationTestSupport {
 *     static JdbcTemplate jdbc;
 *
 *     &#64;BeforeAll
 *     static void migrate() {
 *         jdbc = migrateTo("3");
 *     }
 * }
 * </pre>
 */
@Testcontainers
public abstract class MigrationTestSupport {

    @Container
    protected static final MySQLContainer mysql =
            new MySQLContainer(TestContainersConfig.MYSQL_IMAGE);

    /** 마이그레이션을 끝까지 적용하고, 마지막 버전이 expectedVersion 인지 확인한 뒤 그 DB 의 JdbcTemplate 을 돌려준다. */
    protected static JdbcTemplate migrateTo(String expectedVersion) {
        var dataSource =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var result = Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo(expectedVersion);
        return new JdbcTemplate(dataSource);
    }
}
