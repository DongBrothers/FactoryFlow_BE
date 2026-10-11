package com.factoryflow.test.container;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** 통합 테스트용 MySQL, RabbitMQ 컨테이너. 스프링 컨텍스트 캐시로 테스트 클래스 사이에 재사용된다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

    public static final String MYSQL_IMAGE = "mysql:8.4";
    public static final String RABBITMQ_IMAGE = "rabbitmq:3.13-management";

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer(MYSQL_IMAGE);
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer(RABBITMQ_IMAGE);
    }
}
