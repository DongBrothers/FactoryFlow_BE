package com.factoryflow.production.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.production.scheduler.LineDispatchScheduler;
import com.factoryflow.production.scheduler.LineDispatcher;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
    void schedulerRunsOnlyWhenDispatchIsEnabled() {
        // 꺼 두면(테스트는 build.gradle 로 끈다) 스케줄러가 없어 배정이 저절로 돌지 않는다. 켜거나 설정이 없으면 돈다
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withBean(LineDispatcher.class, () -> new LineDispatcher(null, null))
                        .withUserConfiguration(LineDispatchScheduler.class);

        runner.withPropertyValues("factoryflow.line.dispatch-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LineDispatchScheduler.class));
        runner.withPropertyValues("factoryflow.line.dispatch-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LineDispatchScheduler.class));
        // 설정이 없으면 켠다 (테스트 JVM 의 끄는 설정은 지우고 본다)
        runner.withSystemProperties("factoryflow.line.dispatch-enabled")
                .run(context -> assertThat(context).hasSingleBean(LineDispatchScheduler.class));
    }
}
