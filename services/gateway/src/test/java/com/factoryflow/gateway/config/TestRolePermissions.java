package com.factoryflow.gateway.config;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * @WebFluxTest 용 역할 → 권한 표. 실제로는 auth 가 Redis 로 보내지만, 슬라이스 테스트에는 Redis 가 없어 고정 표를 넣는다. 테스트 역할 이름은
 * 가진 권한을 + 로 이은 것이다 ("READ+ORDER_WRITE" 역할 → READ, ORDER_WRITE 권한). 여기 없는 역할은 권한이 없다.
 */
@TestConfiguration
class TestRolePermissions {

    static final String ALL = "READ+ORDER_WRITE+PRODUCTION_WRITE+PURCHASE_WRITE+ACCOUNT_ADMIN";

    @Bean
    RolePermissionTable rolePermissionTable() {
        RolePermissionTable table = new RolePermissionTable();
        table.replace(
                Stream.of(
                                "READ",
                                "ORDER_WRITE",
                                "PRODUCTION_WRITE",
                                "PURCHASE_WRITE",
                                "ACCOUNT_ADMIN",
                                "READ+ORDER_WRITE",
                                ALL)
                        .collect(
                                Collectors.toMap(
                                        Function.identity(), role -> List.of(role.split("\\+")))));
        return table;
    }
}
