package com.factoryflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.test.container.TestContainersConfig;
import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.resource.ClientResources;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 역할 권한 관리와 gateway 용 표 동기화 (docs/specs/role-permission-admin.md). 실제 MySQL, Redis 를 쓴다. 테스트마다 역할
 * 권한을 V6 처음 값으로 되돌린다.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
class RolePermissionAdminTest {

    static final String TABLE_KEY = "auth:role-permissions";
    static final String CHANNEL = "auth:role-permissions-changed";

    /** V6 이 넣은 표. 역할은 sort_order 순, 권한은 코드 순. */
    static final Map<String, List<String>> V6_TABLE =
            Map.of(
                    "SALES", List.of("ORDER_WRITE", "READ"),
                    "PURCHASE", List.of("PURCHASE_WRITE", "READ"),
                    "PRODUCTION", List.of("PRODUCTION_WRITE", "READ"),
                    "ADMIN", List.of("ACCOUNT_ADMIN"));

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory connectionFactory;
    @Autowired ClientResources clientResources;
    @Autowired ObjectMapper objectMapper;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void restoreV6Permissions() throws Exception {
        setRedisMaxMemory("0");
        // ADMIN 먼저: ACCOUNT_ADMIN 을 가진 역할이 없어지는 순서가 되면 409 다
        for (String role : List.of("ADMIN", "SALES", "PURCHASE", "PRODUCTION")) {
            replace(role, V6_TABLE.get(role)).andExpect(status().isOk());
        }
    }

    // --- 조회 ---

    @Test
    void listsRolesWithTheirPermissions() throws Exception {
        mockMvc.perform(get("/auth/admin/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("SALES"))
                .andExpect(jsonPath("$.data[0].permissions[0]").value("ORDER_WRITE"))
                .andExpect(jsonPath("$.data[0].permissions[1]").value("READ"))
                .andExpect(jsonPath("$.data[3].code").value("ADMIN"))
                .andExpect(jsonPath("$.data[3].permissions[0]").value("ACCOUNT_ADMIN"))
                .andExpect(jsonPath("$.data[3].permissions.length()").value(1));
    }

    @Test
    void listsPermissionsInCodeOrder() throws Exception {
        mockMvc.perform(get("/auth/admin/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("ACCOUNT_ADMIN"))
                .andExpect(jsonPath("$.data[0].name").value("계정 관리"))
                .andExpect(jsonPath("$.data[1].code").value("ORDER_WRITE"))
                .andExpect(jsonPath("$.data[2].code").value("PRODUCTION_WRITE"))
                .andExpect(jsonPath("$.data[3].code").value("PURCHASE_WRITE"))
                .andExpect(jsonPath("$.data[4].code").value("READ"));
    }

    // --- 바꾸기 ---

    @Test
    void replacesRolePermissions() throws Exception {
        // 같은 코드가 두 번 와도 한 번으로 본다
        replace("SALES", List.of("READ", "ORDER_WRITE", "PURCHASE_WRITE", "READ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("SALES"))
                .andExpect(jsonPath("$.data.name").value("영업"))
                .andExpect(jsonPath("$.data.permissions.length()").value(3))
                .andExpect(jsonPath("$.data.permissions[0]").value("ORDER_WRITE"))
                .andExpect(jsonPath("$.data.permissions[1]").value("PURCHASE_WRITE"))
                .andExpect(jsonPath("$.data.permissions[2]").value("READ"));

        assertThat(permissionsInDb("SALES"))
                .containsExactlyInAnyOrder("READ", "ORDER_WRITE", "PURCHASE_WRITE");
    }

    @Test
    void roleCanHaveNoPermissions() throws Exception {
        replace("PRODUCTION", List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.length()").value(0));

        assertThat(permissionsInDb("PRODUCTION")).isEmpty();
        assertThat(tableInRedis()).containsEntry("PRODUCTION", List.of());
    }

    @Test
    void replaceWritesTableToRedisAndPublishesIt() throws Exception {
        BlockingQueue<String> published = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer subscriber = subscribe(published);

        replace("SALES", List.of("READ", "ORDER_WRITE", "PURCHASE_WRITE"))
                .andExpect(status().isOk());

        Map<String, List<String>> expected = new HashMap<>(V6_TABLE);
        expected.put("SALES", List.of("ORDER_WRITE", "PURCHASE_WRITE", "READ"));
        assertThat(tableInRedis()).isEqualTo(expected);
        // 구독하며 생긴 연결에 auth 가 표를 한 번 더 보낼 수 있어, 바뀐 표가 올 때까지 본다
        Map<String, List<String>> received = null;
        for (int i = 0; i < 5 && !expected.equals(received); i++) {
            String message = published.poll(1, TimeUnit.SECONDS);
            received = message == null ? null : parse(message);
        }
        subscriber.stop();
        assertThat(received).isEqualTo(expected);
    }

    @Test
    void rejectsUnknownRole() throws Exception {
        replace("NOPE", List.of("READ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTH-014"));
    }

    @Test
    void rejectsUnknownPermission() throws Exception {
        replace("SALES", List.of("READ", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH-015"));

        assertThat(permissionsInDb("SALES")).containsExactlyInAnyOrder("READ", "ORDER_WRITE");
    }

    @Test
    void rejectsInvalidRequest() throws Exception {
        // permissions 가 없거나 빈 코드가 있으면 형식 오류
        mockMvc.perform(
                        put("/auth/admin/roles/SALES/permissions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        replace("SALES", List.of("READ", " ")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsChangeThatLeavesNoRoleWithAccountAdmin() throws Exception {
        // 아무 역할도 ACCOUNT_ADMIN 을 갖지 않으면 누구도 권한을 되돌릴 수 없다
        replace("ADMIN", List.of("READ"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH-016"));

        assertThat(permissionsInDb("ADMIN")).containsExactly("ACCOUNT_ADMIN");
        assertThat(tableInRedis()).containsEntry("ADMIN", List.of("ACCOUNT_ADMIN"));
    }

    @Test
    void accountAdminCanMoveToAnotherRole() throws Exception {
        // 역할 이름이 아니라 "ACCOUNT_ADMIN 을 가진 역할이 하나는 남는가" 로 본다
        replace("SALES", List.of("READ", "ORDER_WRITE", "ACCOUNT_ADMIN"))
                .andExpect(status().isOk());

        replace("ADMIN", List.of()).andExpect(status().isOk());

        assertThat(permissionsInDb("ADMIN")).isEmpty();
        // 되돌리기 전에 ACCOUNT_ADMIN 을 ADMIN 에 먼저 돌려준다 (SALES 를 먼저 되돌리면 409)
        replace("ADMIN", List.of("ACCOUNT_ADMIN")).andExpect(status().isOk());
    }

    @Test
    void redisWriteFailureReturns503AndSavingAgainRewritesTable() throws Exception {
        // 메모리 한도를 넘게 만들어 Redis 쓰기를 실패시킨다 (OOM command not allowed)
        setRedisMaxMemory("1");
        replace("SALES", List.of("READ"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH-017"));
        // DB 는 이미 바뀌었다
        assertThat(permissionsInDb("SALES")).containsExactly("READ");

        setRedisMaxMemory("0");
        replace("SALES", List.of("READ")).andExpect(status().isOk());

        assertThat(tableInRedis()).containsEntry("SALES", List.of("READ"));
    }

    // --- 시작, 재연결 ---

    @Test
    void tableIsInRedisAfterStartup() {
        // auth 가 뜰 때 쓴 표 (테스트마다 V6 값으로 되돌리므로 같은 값이다)
        assertThat(tableInRedis()).isEqualTo(V6_TABLE);
    }

    @Test
    void reconnectToRedisRewritesTable() throws Exception {
        // Redis 가 재시작해 키가 사라진 상황. 다시 연결되면 auth 가 표를 다시 쓴다
        redis.delete(TABLE_KEY);

        clientResources
                .eventBus()
                .publish(
                        new ConnectionActivatedEvent(
                                new InetSocketAddress("localhost", 1),
                                new InetSocketAddress("localhost", 6379)));

        for (int i = 0; i < 50 && !redis.hasKey(TABLE_KEY); i++) {
            Thread.sleep(100);
        }
        assertThat(tableInRedis()).isEqualTo(V6_TABLE);
    }

    // --- helpers ---

    private ResultActions replace(String role, List<String> permissions) throws Exception {
        return mockMvc.perform(
                put("/auth/admin/roles/" + role + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                objectMapper.writeValueAsString(
                                        Map.of("permissions", permissions))));
    }

    private List<String> permissionsInDb(String role) {
        return jdbc.queryForList(
                "SELECT p.code FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                        + " JOIN permission p ON p.id = rp.permission_id WHERE r.code = ?",
                String.class,
                role);
    }

    private Map<String, List<String>> tableInRedis() {
        return parse(redis.opsForValue().get(TABLE_KEY));
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parse(String json) {
        assertThat(json).isNotNull();
        return objectMapper.readValue(json, Map.class);
    }

    private void setRedisMaxMemory(String bytes) {
        redis.execute(
                (RedisCallback<Void>)
                        connection -> {
                            connection.serverCommands().setConfig("maxmemory", bytes);
                            return null;
                        });
    }

    /** 채널을 구독하고, 구독이 Redis 에 걸릴 때까지 기다린다. */
    private RedisMessageListenerContainer subscribe(BlockingQueue<String> messages)
            throws InterruptedException {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) ->
                        messages.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(CHANNEL));
        container.afterPropertiesSet();
        container.start();
        for (int i = 0; i < 50 && !container.isListening(); i++) {
            Thread.sleep(100);
        }
        return container;
    }
}
