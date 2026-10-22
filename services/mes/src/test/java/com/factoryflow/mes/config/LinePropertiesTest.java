package com.factoryflow.mes.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.mes.scheduler.LineDispatchScheduler;
import com.factoryflow.mes.scheduler.LineDispatcher;
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
    void lineSettingsLiveInEachEnvironmentFileNotTheCommonOne() throws Exception {
        // 환경마다 다른 값은 application-{local,dev,test}.yml 에 둔다. 공통 application.yml 에는 없다
        PropertySource<?> common = load("application.yml");
        assertThat(common.getProperty("factoryflow.line.dispatch-enabled")).isNull();
        assertThat(common.getProperty("factoryflow.line.start-report-timeout")).isNull();

        // 테스트 환경은 배정 스케줄러를 끄고, 배정은 테스트가 직접 부른다
        PropertySource<?> test = load("application-test.yml");
        assertThat(test.getProperty("factoryflow.line.dispatch-enabled")).isEqualTo(false);
        assertThat(test.getProperty("factoryflow.line.start-report-timeout")).isEqualTo("1m");
    }

    private static PropertySource<?> load(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).get(0);
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
