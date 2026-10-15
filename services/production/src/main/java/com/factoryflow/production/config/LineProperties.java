package com.factoryflow.production.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 로봇 공정 배정 설정 (docs/specs/production-work-order.md "3. 로봇 공정 자동 진행").
 *
 * @param dispatchEnabled 배정 스케줄러를 돌릴지 (테스트는 끈다)
 * @param dispatchInterval 배정 주기
 * @param pressHold 작업지시 발행 후 프레스를 배정하기까지 기다리는 시간. 이 사이에는 취소할 수 있다
 */
@ConfigurationProperties(prefix = "factoryflow.line")
public record LineProperties(
        @DefaultValue("true") boolean dispatchEnabled,
        @DefaultValue("5s") Duration dispatchInterval,
        @DefaultValue("1m") Duration pressHold) {}
