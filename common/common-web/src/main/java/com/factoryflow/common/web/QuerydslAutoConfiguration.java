package com.factoryflow.common.web;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** QueryDSL 의존성을 넣은 서비스에만 JPAQueryFactory 를 만든다. 서비스가 직접 만들면 그것을 쓴다. */
@AutoConfiguration
@ConditionalOnClass({JPAQueryFactory.class, EntityManager.class})
public class QuerydslAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
