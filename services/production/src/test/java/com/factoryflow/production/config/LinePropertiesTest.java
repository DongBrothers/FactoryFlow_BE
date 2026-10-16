package com.factoryflow.production.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 배정 스케줄러 설정: 기본은 켜고, 테스트는 끈다 (docs/specs/simulator.md "5. production 쪽 변경"). */
class LinePropertiesTest {

    @Test
    void defaultsAreDispatchOnEveryFiveSecondsWithAOneMinuteStartReportTimeout() {
        LineProperties properties =
                new Binder(new MapConfigurationPropertySource(Map.of()))
                        .bindOrCreate("factoryflow.line", LineProperties.class);

        assertThat(properties.dispatchEnabled()).isTrue();
        assertThat(properties.dispatchInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.startReportTimeout()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void applicationYmlTurnsTheDispatcherOn() throws Exception {
        PropertySource<?> yml =
                new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yml"))
                        .get(0);

        assertThat(yml.getProperty("factoryflow.line.dispatch-enabled")).isEqualTo(true);
        assertThat(yml.getProperty("factoryflow.line.start-report-timeout")).isEqualTo("1m");
    }

    @Test
    void testsRunWithTheDispatcherOff() {
        // build.gradle 의 test 작업이 끈다. 테스트는 LineDispatcher 를 직접 불러 배정을 확인한다
        assertThat(System.getProperty("factoryflow.line.dispatch-enabled")).isEqualTo("false");
    }
}
