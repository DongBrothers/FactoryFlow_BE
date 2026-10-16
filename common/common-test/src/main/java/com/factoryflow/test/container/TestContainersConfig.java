package com.factoryflow.test.container;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * 통합 테스트용 MySQL, RabbitMQ, Redis 컨테이너. 스프링 컨텍스트 캐시로 테스트 클래스 사이에 재사용된다. MySQL 은 JDBC 를 쓰는 서비스에만 띄운다
 * (DB 없는 simulator 는 MySQL 연결 정보를 받을 곳이 없어 실패한다).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

    public static final String MYSQL_IMAGE = "mysql:8.4";
    public static final String RABBITMQ_IMAGE = "rabbitmq:3.13-management";
    public static final String REDIS_IMAGE = "redis:7.4";

    @Bean
    @ServiceConnection
    @ConditionalOnClass(name = "org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails")
    MySQLContainer mysql() {
        return new MySQLContainer(MYSQL_IMAGE);
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer(RABBITMQ_IMAGE);
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }
}
