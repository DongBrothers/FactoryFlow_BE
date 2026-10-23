package com.factoryflow.simulator.config;

import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 가상 설비 설정, 작업 시간·재전송·자동 고장 스케줄. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 불량·고장 확률을 뽑는다. JRE 이미지에 jdk.random 모듈이 없을 수 있어 java.base 의 Random 을 쓴다 (getDefault 는
     * jdk.random).
     */
    @Bean
    RandomGenerator randomGenerator() {
        return new Random();
    }
}
