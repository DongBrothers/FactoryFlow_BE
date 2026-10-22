package com.factoryflow.mes.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * 센서값 테스트용 MongoDB 컨테이너 (docs/specs/process-telemetry.md). 센서값을 쓰는 테스트만 TestContainersConfig 와 함께
 * 가져온다. 다른 mes 테스트는 MongoDB 없이 돈다 (컬렉션은 처음 저장할 때 만든다).
 */
@TestConfiguration(proxyBeanMethods = false)
public class MongoContainerConfig {

    static final String MONGO_IMAGE = "mongo:7";

    @Bean
    @ServiceConnection
    MongoDBContainer mongoContainer() {
        return new MongoDBContainer(MONGO_IMAGE);
    }
}
