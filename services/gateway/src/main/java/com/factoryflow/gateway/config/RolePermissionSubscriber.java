package com.factoryflow.gateway.config;

import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.resource.ClientResources;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * auth 가 Redis 로 보내는 역할 → 권한 표를 받아 RolePermissionTable 에 넣는다 (docs/specs/role-permission-admin.md).
 * gateway 는 auth 를 부르지 않는다.
 *
 * <ul>
 *   <li>뜰 때와 Redis 에 다시 연결됐을 때: 키(auth:role-permissions)를 읽는다. 끊긴 동안 온 신호를 놓쳤을 수 있다
 *   <li>권한이 바뀔 때: 채널(auth:role-permissions-changed)로 온 표로 바꾼다
 *   <li>키가 없거나 읽지 못하면 들고 있는 표를 그대로 두고, 읽을 때까지 retryInterval 마다 다시 읽는다
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RolePermissionProperties.class)
public class RolePermissionSubscriber {

    private static final String TABLE_KEY = "auth:role-permissions";
    private static final String CHANNEL = "auth:role-permissions-changed";
    private static final TypeReference<Map<String, List<String>>> TABLE_TYPE =
            new TypeReference<>() {};

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RolePermissionTable table;
    private final ClientResources clientResources;
    private final RolePermissionProperties properties;

    private final Disposable.Composite subscriptions = Disposables.composite();
    private final Disposable.Swap keyLoad = Disposables.swap();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        subscriptions.add(
                redis.listenToChannel(CHANNEL)
                        .map(ReactiveSubscription.Message::getMessage)
                        .subscribe(
                                this::replaceFrom, e -> log.error("[gateway] 권한 표 채널 구독 실패", e)));
        subscriptions.add(
                clientResources
                        .eventBus()
                        .get()
                        .filter(ConnectionActivatedEvent.class::isInstance)
                        .subscribe(event -> loadFromKey()));
        loadFromKey();
    }

    /** 키를 읽어 표를 바꾼다. 없거나 실패하면 읽을 때까지 다시 읽는다. 앞서 다시 읽던 것은 멈춘다. */
    private void loadFromKey() {
        keyLoad.update(
                redis.opsForValue()
                        .get(TABLE_KEY)
                        .switchIfEmpty(Mono.error(new IllegalStateException("권한 표 키가 없음")))
                        .retryWhen(
                                Retry.fixedDelay(Long.MAX_VALUE, properties.retryInterval())
                                        .doBeforeRetry(
                                                signal -> {
                                                    if (signal.totalRetries() == 0) {
                                                        log.warn(
                                                                "[gateway] 권한 표를 읽지 못함, 다시 읽는다: {}",
                                                                signal.failure().getMessage());
                                                    }
                                                }))
                        .subscribe(this::replaceFrom));
    }

    /** 잘못된 표가 오면 들고 있는 표를 그대로 둔다. */
    private void replaceFrom(String json) {
        try {
            table.replace(objectMapper.readValue(json, TABLE_TYPE));
        } catch (RuntimeException e) {
            log.error("[gateway] 권한 표를 읽을 수 없음", e);
        }
    }

    @PreDestroy
    void stop() {
        keyLoad.dispose();
        subscriptions.dispose();
    }
}
