package com.factoryflow.common.web;

import com.factoryflow.common.web.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class WebAutoConfiguration {

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * {@link BaseEntity} 의 createdAt/updatedAt 자동 기록. 자동 설정에 두면 @WebMvcTest 같은 슬라이스 테스트에는 로드되지 않는다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EnableJpaAuditing.class)
    @EnableJpaAuditing
    static class JpaAuditingConfiguration {}
}
