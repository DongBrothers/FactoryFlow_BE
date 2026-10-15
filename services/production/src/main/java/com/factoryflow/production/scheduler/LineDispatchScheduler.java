package com.factoryflow.production.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 로봇 공정 배정을 주기적으로 돌린다 (factoryflow.line.dispatch-interval). 테스트는 dispatch-enabled=false 로 끈다. */
@Component
@ConditionalOnProperty(
        prefix = "factoryflow.line",
        name = "dispatch-enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class LineDispatchScheduler {

    private final LineDispatcher lineDispatcher;

    @Scheduled(fixedDelayString = "${factoryflow.line.dispatch-interval:5s}")
    public void dispatch() {
        lineDispatcher.dispatchAll();
    }
}
