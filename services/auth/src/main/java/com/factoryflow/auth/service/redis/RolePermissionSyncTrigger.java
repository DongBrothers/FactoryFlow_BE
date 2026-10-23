package com.factoryflow.auth.service.redis;

import com.factoryflow.auth.service.RoleService;
import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.resource.ClientResources;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

/**
 * gateway 용 역할 권한 표를 Redis 에 다시 쓰는 때: auth 가 뜰 때(Flyway 다음, 마이그레이션으로 바뀐 표), Redis 에 다시 연결됐을 때(Redis
 * 가 재시작해 키가 사라졌을 수 있다). 주기적으로는 쓰지 않는다 (docs/specs/role-permission-admin.md).
 */
@Component
@RequiredArgsConstructor
public class RolePermissionSyncTrigger {

    private final RoleService roleService;
    private final ClientResources clientResources;

    private Disposable reconnects;

    @EventListener(ApplicationReadyEvent.class)
    public void broadcastOnStartupAndReconnect() {
        roleService.broadcastQuietly();
        // 연결 이벤트는 Lettuce 스레드에서 오므로, Redis 를 다시 부르는 일은 다른 스레드에서 한다
        reconnects =
                clientResources
                        .eventBus()
                        .get()
                        .filter(ConnectionActivatedEvent.class::isInstance)
                        .publishOn(Schedulers.boundedElastic())
                        .subscribe(event -> roleService.broadcastQuietly());
    }

    @PreDestroy
    void stopWatchingReconnects() {
        if (reconnects != null) {
            reconnects.dispose();
        }
    }
}
