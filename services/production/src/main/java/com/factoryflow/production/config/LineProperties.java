package com.factoryflow.production.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 로봇 공정 배정 설정 (docs/specs/production-work-order.md "3. 로봇 공정 자동 진행").
 *
 * @param dispatchEnabled 배정 스케줄러를 돌릴지 (테스트는 끈다)
 * @param dispatchInterval 배정 주기
 * @param startReportTimeout 배정 뒤 설비의 시작 보고를 기다리는 시간. 넘으면 그 공정을 되돌려 다시 배정한다 (설비 재시작, 통신 끊김)
 * @param completionReportTimeout 설비의 시작 보고 뒤 완료 보고를 기다리는 시간. 넘으면 그 공정을 되돌려 다시 배정한다. 작업자가 시작한 공정은 보지
 *     않는다
 */
@ConfigurationProperties(prefix = "factoryflow.line")
public record LineProperties(
        @DefaultValue("true") boolean dispatchEnabled,
        @DefaultValue("5s") Duration dispatchInterval,
        @DefaultValue("1m") Duration startReportTimeout,
        @DefaultValue("5m") Duration completionReportTimeout) {}
