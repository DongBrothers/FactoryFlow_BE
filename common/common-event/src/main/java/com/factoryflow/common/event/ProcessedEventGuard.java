package com.factoryflow.common.event;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** 수신 멱등 처리. processed_event 에 eventId 를 기록하고, 처음 받은 이벤트일 때만 action 을 같은 트랜잭션에서 실행한다. */
public class ProcessedEventGuard {

    private final EntityManager entityManager;
    private final Clock clock;

    public ProcessedEventGuard(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * @return action 을 실행했으면 true, 이미 처리된 이벤트라 건너뛰었으면 false
     */
    @Transactional
    public boolean runOnce(UUID eventId, Runnable action) {
        // 중복이면 오류 대신 0줄. 오류가 없으니 트랜잭션이 롤백 전용이 되지 않아 바깥 트랜잭션 안에서도 그대로 커밋된다
        int inserted =
                entityManager
                        .createNativeQuery(
                                "INSERT IGNORE INTO processed_event (event_id, processed_at)"
                                        + " VALUES (?1, ?2)")
                        .setParameter(1, eventId.toString())
                        .setParameter(
                                2, LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()))
                        .executeUpdate();
        if (inserted == 0) {
            return false;
        }
        action.run();
        return true;
    }
}
