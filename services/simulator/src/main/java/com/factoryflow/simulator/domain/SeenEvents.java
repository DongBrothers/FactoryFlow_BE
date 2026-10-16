package com.factoryflow.simulator.domain;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 받은 eventId (메모리, 최근 limit 개). 같은 메시지를 두 번 처리하지 않게 한다. ProcessedEventGuard 대신이다 (DB 없음). */
public class SeenEvents {

    private final Set<UUID> ids = new LinkedHashSet<>();

    /** 처음 보면 기억하고 true. 이미 봤으면 false. 넘치면 가장 오래된 것을 잊는다. */
    public synchronized boolean markIfNew(UUID eventId, int limit) {
        if (!ids.add(eventId)) {
            return false;
        }
        while (ids.size() > limit) {
            ids.remove(ids.iterator().next());
        }
        return true;
    }
}
