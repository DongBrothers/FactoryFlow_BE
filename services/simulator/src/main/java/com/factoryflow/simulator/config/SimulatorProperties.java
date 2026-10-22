package com.factoryflow.simulator.config;

import com.factoryflow.simulator.domain.LineProcess;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 가상 설비 설정 (docs/specs/simulator.md "설정").
 *
 * @param startDelay 배정을 받고 시작 보고까지
 * @param workTime 공정별 시작 보고부터 완료 보고까지
 * @param defectRate 완료가 불량일 확률 (0~1)
 * @param faultCheckInterval 자동 고장을 뽑는 주기
 * @param faultRate 주기마다 가동 중인 설비마다 고장 날 확률 (0 이면 자동 고장 없음)
 * @param repairTime 자동 고장에서 가동으로 돌아오기까지
 * @param resendInterval 브로커 확인을 못 받은 메시지를 다시 보내는 주기
 * @param seenEventLimit 중복 확인용으로 기억하는 eventId 개수
 * @param sampleInterval 로봇 공정 중 센서값을 재는 간격 (50ms 미만이면 50ms, 공정당 최대 300개,
 *     docs/specs/process-telemetry.md)
 */
@ConfigurationProperties(prefix = "factoryflow.simulator")
public record SimulatorProperties(
        @DefaultValue("1s") Duration startDelay,
        Map<LineProcess, Duration> workTime,
        @DefaultValue("0.05") double defectRate,
        @DefaultValue("30s") Duration faultCheckInterval,
        @DefaultValue("0") double faultRate,
        @DefaultValue("60s") Duration repairTime,
        @DefaultValue("1s") Duration resendInterval,
        @DefaultValue("10000") int seenEventLimit,
        @DefaultValue("200ms") Duration sampleInterval) {

    /** 작업 시간 설정이 없는 공정의 작업 시간. */
    public static final Duration DEFAULT_WORK_TIME = Duration.ofSeconds(3);

    /** 공정의 작업 시간. 설정이 없으면 DEFAULT_WORK_TIME. */
    public Duration workTimeOf(LineProcess process) {
        return workTime == null
                ? DEFAULT_WORK_TIME
                : workTime.getOrDefault(process, DEFAULT_WORK_TIME);
    }
}
