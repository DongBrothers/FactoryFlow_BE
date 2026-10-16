package com.factoryflow.simulator.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 브로커 확인(ack)을 아직 못 받은 메시지 (메모리). 보낼 때 넣고, ack 를 받으면 뺀다. 마지막으로 보낸 지 resendInterval 이 지난 것은 다시 보낸다.
 * Outbox 대신 쓰므로 재시작하면 사라진다 (docs/specs/simulator.md "4. 보내기").
 */
public class PendingMessages<T> {

    private final Map<String, T> messages = new LinkedHashMap<>();
    private final Map<String, Instant> sentAt = new LinkedHashMap<>();

    public synchronized void sent(String id, T message, Instant now) {
        messages.put(id, message);
        sentAt.put(id, now);
    }

    public synchronized void acked(String id) {
        messages.remove(id);
        sentAt.remove(id);
    }

    /** 다시 보낼 메시지, 보낸 순서로. 꺼낸 것은 보낸 시각을 now 로 바꾼다 (다음 주기까지 기다림). */
    public synchronized List<T> dueForResend(Instant now, Duration resendInterval) {
        List<String> dueIds =
                sentAt.entrySet().stream()
                        .filter(
                                e ->
                                        Duration.between(e.getValue(), now)
                                                        .compareTo(resendInterval)
                                                >= 0)
                        .map(Map.Entry::getKey)
                        .toList();
        dueIds.forEach(id -> sentAt.put(id, now));
        return dueIds.stream().map(messages::get).toList();
    }

    public synchronized int size() {
        return messages.size();
    }
}
