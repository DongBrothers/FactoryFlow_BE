package com.factoryflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.test.container.TestContainersConfig;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 계정 발급과 인증 (docs/specs/auth-account.md). 실제 MySQL, Redis 를 쓴다. */
@SpringBootTest
@Import(TestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthAccountTest {

    static final String EMAIL = "kim@gmail.com";
    static final String PASSWORD = "pw-1234!";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM user_account");
        var keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // --- 발급 ---

    @Test
    void issueStoresPendingInRedisOnlyAndNotInDb() throws Exception {
        issue(EMAIL)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.name").value("김철수"))
                .andExpect(jsonPath("$.data.role").value("SALES"))
                .andExpect(jsonPath("$.data.id").doesNotExist());

        assertThat(userCount()).isZero();
        String token = pendingToken(EMAIL);
        assertThat(token).isNotNull();
        assertThat(redis.hasKey("auth:activation:" + token)).isTrue();
        assertTtlAbout24Hours("auth:activation:" + token);
        assertTtlAbout24Hours("auth:pending:" + EMAIL);
    }

    @Test
    void issueRejectsDisallowedDomain() throws Exception {
        issue("kim@naver.com")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH-003"));

        assertThat(redis.keys("auth:*")).isEmpty();
    }

    @Test
    void issueRejectsInvalidRequest() throws Exception {
        mockMvc.perform(
                        post("/auth/admin/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"not-an-email\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void issueRejectsNameLongerThanColumn() throws Exception {
        // user_account.name 은 50자. 발급 때 막지 않으면 활성화 때 저장이 실패한다
        String name = "가".repeat(51);
        mockMvc.perform(
                        post("/auth/admin/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\""
                                                + EMAIL
                                                + "\",\"name\":\""
                                                + name
                                                + "\",\"role\":\"SALES\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(redis.keys("auth:*")).isEmpty();
    }

    @Test
    void issueRejectsAlreadyActivatedEmail() throws Exception {
        activateNewAccount(EMAIL);

        issue(EMAIL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
    }

    @Test
    void reissueInvalidatesOldLink() throws Exception {
        issue(EMAIL).andExpect(status().isCreated());
        String oldToken = pendingToken(EMAIL);

        issue(EMAIL).andExpect(status().isCreated());
        String newToken = pendingToken(EMAIL);

        assertThat(newToken).isNotEqualTo(oldToken);
        activate(oldToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH-004"));
        activate(newToken).andExpect(status().isNoContent());
    }

    // --- 활성화 ---

    @Test
    void activateSavesActiveAccountAndClearsRedis() throws Exception {
        issue(EMAIL).andExpect(status().isCreated());
        String token = pendingToken(EMAIL);

        activate(token).andExpect(status().isNoContent());

        var row =
                jdbc.queryForMap(
                        "SELECT public_id, email, password, name, role, status FROM user_account");
        assertThat(row)
                .containsEntry("email", EMAIL)
                .containsEntry("name", "김철수")
                .containsEntry("role", "SALES")
                .containsEntry("status", "ACTIVE");
        assertThat((String) row.get("public_id")).hasSize(36);
        assertThat((String) row.get("password")).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(redis.hasKey("auth:activation:" + token)).isFalse();
        assertThat(redis.hasKey("auth:pending:" + EMAIL)).isFalse();
    }

    @Test
    void activateRejectsUnknownOrUsedToken() throws Exception {
        activate("does-not-exist")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH-004"));

        issue(EMAIL).andExpect(status().isCreated());
        String token = pendingToken(EMAIL);
        activate(token).andExpect(status().isNoContent());

        activate(token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH-004"));
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void activateReturnsConflictWhenEmailAlreadySaved() throws Exception {
        issue(EMAIL).andExpect(status().isCreated());
        String token = pendingToken(EMAIL);
        // 같은 이메일이 다른 경로(거의 동시의 두 번째 발급)로 먼저 저장된 상황
        jdbc.update(
                "INSERT INTO user_account (public_id, email, password, name, role, status,"
                        + " created_at, updated_at)"
                        + " VALUES (UUID(), ?, 'hash', '다른사람', 'WORKER', 'ACTIVE', NOW(6), NOW(6))",
                EMAIL);

        activate(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void activateKeepsTokenWhenSaveFails() throws Exception {
        // 중복이 아닌 이유로 저장이 실패하면(여기서는 컬럼 길이 초과로 재현) 토큰을 되돌려, 직원이 같은 링크로 다시 시도할 수 있다
        String token = "restore-test-token";
        redis.opsForHash()
                .putAll(
                        "auth:activation:" + token,
                        Map.of("email", EMAIL, "name", "가".repeat(60), "role", "SALES"));
        redis.expire("auth:activation:" + token, Duration.ofHours(24));
        redis.opsForValue().set("auth:pending:" + EMAIL, token, Duration.ofHours(24));

        activate(token).andExpect(status().isInternalServerError());

        assertThat(userCount()).isZero();
        assertThat(redis.hasKey("auth:activation:" + token)).isTrue();
        assertThat(pendingToken(EMAIL)).isEqualTo(token);
    }

    // --- 로그인 ---

    @Test
    void loginIssuesAccessAndRefreshCookie() throws Exception {
        activateNewAccount(EMAIL);

        String body =
                login(EMAIL, PASSWORD)
                        .andExpect(status().isOk())
                        .andExpect(cookie().httpOnly("refreshToken", true))
                        .andExpect(cookie().path("refreshToken", "/api/auth"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        SignedJWT jwt = SignedJWT.parse(JsonPath.read(body, "$.data.accessToken"));
        String jwks =
                mockMvc.perform(get("/internal/jwks"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var publicKey = JWKSet.parse(jwks).getKeys().get(0).toRSAKey();
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        String publicId = jdbc.queryForObject("SELECT public_id FROM user_account", String.class);
        assertThat(claims.getSubject()).isEqualTo(publicId);
        assertThat(claims.getStringClaim("role")).isEqualTo("SALES");
        assertThat(claims.getClaims()).doesNotContainKeys("email", "employeeNo");
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        issue("Kim@Gmail.COM").andExpect(status().isCreated());
        activate(pendingToken(EMAIL)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT email FROM user_account", String.class))
                .isEqualTo(EMAIL);
        login("KIM@gmail.com", PASSWORD).andExpect(status().isOk());
        issue("KIM@GMAIL.COM").andExpect(status().isConflict());
    }

    @Test
    void loginIgnoresSurroundingSpacesInEmail() throws Exception {
        activateNewAccount(EMAIL);

        login("  " + EMAIL + " ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void issueRejectsEmailWithSurroundingSpaces() throws Exception {
        // 발급은 이메일 형식 검사(@Email)를 먼저 하므로 앞뒤 공백이 있으면 형식 오류로 알려 준다
        issue(" " + EMAIL + " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(redis.keys("auth:*")).isEmpty();
    }

    @Test
    void loginRejectsWrongPasswordOrUnknownEmail() throws Exception {
        activateNewAccount(EMAIL);

        login(EMAIL, "wrong")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
        login("nobody@gmail.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void loginRejectsDisabledAccount() throws Exception {
        activateNewAccount(EMAIL);
        jdbc.update("UPDATE user_account SET status = 'DISABLED'");

        login(EMAIL, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-007"));
    }

    @Test
    void loginLocksAfterMaxFailures() throws Exception {
        activateNewAccount(EMAIL);
        for (int i = 0; i < 5; i++) {
            login(EMAIL, "wrong").andExpect(status().isUnauthorized());
        }

        // 잠긴 동안은 맞는 비밀번호도 거절된다
        login(EMAIL, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH-008"));
    }

    // --- 재발급·로그아웃 ---

    @Test
    void refreshRotatesToken() throws Exception {
        activateNewAccount(EMAIL);
        Cookie first = refreshCookie(login(EMAIL, PASSWORD));

        Cookie second =
                refreshCookie(
                        mockMvc.perform(post("/auth/token/refresh").cookie(first))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.accessToken").isNotEmpty()));

        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        mockMvc.perform(post("/auth/token/refresh").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-009"));
    }

    @Test
    void refreshRejectsMissingCookie() throws Exception {
        mockMvc.perform(post("/auth/token/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-009"));
    }

    @Test
    void refreshRejectsDisabledAccount() throws Exception {
        // 로그인 뒤 비활성화된 계정은 유효한 Refresh 가 있어도 재발급되지 않는다
        activateNewAccount(EMAIL);
        Cookie refresh = refreshCookie(login(EMAIL, PASSWORD));
        jdbc.update("UPDATE user_account SET status = 'DISABLED'");

        mockMvc.perform(post("/auth/token/refresh").cookie(refresh))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-007"));
    }

    @Test
    void logoutRevokesRefreshAndExpiresCookie() throws Exception {
        activateNewAccount(EMAIL);
        Cookie refresh = refreshCookie(login(EMAIL, PASSWORD));

        mockMvc.perform(post("/auth/logout").cookie(refresh))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("refreshToken", 0));

        mockMvc.perform(post("/auth/token/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/logout")).andExpect(status().isNoContent());
    }

    // --- 로그 ---

    @Test
    void doesNotLogPasswordTokenOrEmail(CapturedOutput output) throws Exception {
        issue(EMAIL).andExpect(status().isCreated());
        String token = pendingToken(EMAIL);
        activate(token).andExpect(status().isNoContent());
        Cookie refresh = refreshCookie(login(EMAIL, PASSWORD));
        login(EMAIL, "wrong-password").andExpect(status().isUnauthorized());

        assertThat(output)
                .doesNotContain(EMAIL)
                .doesNotContain(PASSWORD)
                .doesNotContain("wrong-password")
                .doesNotContain(token)
                .doesNotContain(refresh.getValue());
    }

    // --- API 문서 ---

    @Test
    void apiDocsListActualResponseCodesAndFieldDescriptions() throws Exception {
        String docs =
                mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // 명세 "API 별 응답" 표의 코드만 나오고, 공통 핸들러의 500 은 자동으로 붙지 않는다
        assertResponseCodes(docs, "/auth/admin/users", "201", "400", "409");
        assertResponseCodes(docs, "/auth/activation", "204", "400", "409");
        assertResponseCodes(docs, "/auth/login", "200", "400", "401", "403", "429");
        assertResponseCodes(docs, "/auth/token/refresh", "200", "401", "403");
        assertResponseCodes(docs, "/auth/logout", "204");
        // 서버끼리만 쓰는 JWKS 는 다른 내부 API 처럼 프론트가 보는 문서에 나오지 않는다
        assertThat(JsonPath.<Map<String, Object>>read(docs, "$.paths"))
                .doesNotContainKey("/internal/jwks");

        assertThat(
                        JsonPath.<String>read(
                                docs,
                                "$.components.schemas.IssueAccountRequest.properties.email.description"))
                .isNotBlank();
        assertThat(
                        JsonPath.<String>read(
                                docs,
                                "$.components.schemas.AccessTokenResponse.properties.accessToken.description"))
                .isNotBlank();
    }

    private static void assertResponseCodes(String docs, String path, String... codes) {
        assertThat(JsonPath.<String>read(docs, "$.paths['" + path + "'].post.summary"))
                .isNotBlank();
        Map<String, Object> responses =
                JsonPath.read(docs, "$.paths['" + path + "'].post.responses");
        assertThat(responses.keySet()).containsExactlyInAnyOrder(codes);
    }

    // --- helpers ---

    private ResultActions issue(String email) throws Exception {
        return mockMvc.perform(
                post("/auth/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(email)));
    }

    private static String issueBody(String email) {
        return "{\"email\":\"" + email + "\",\"name\":\"김철수\",\"role\":\"SALES\"}";
    }

    private ResultActions activate(String token) throws Exception {
        return mockMvc.perform(
                post("/auth/activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(
                post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private void activateNewAccount(String email) throws Exception {
        issue(email).andExpect(status().isCreated());
        activate(pendingToken(email)).andExpect(status().isNoContent());
    }

    private String pendingToken(String email) {
        return redis.opsForValue().get("auth:pending:" + email);
    }

    private Cookie refreshCookie(ResultActions result) throws Exception {
        Cookie cookie = result.andReturn().getResponse().getCookie("refreshToken");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private int userCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class);
    }

    private void assertTtlAbout24Hours(String key) {
        Long seconds = redis.getExpire(key);
        assertThat(seconds).isNotNull();
        assertThat(Duration.ofSeconds(seconds))
                .isGreaterThan(Duration.ofHours(23))
                .isLessThanOrEqualTo(Duration.ofHours(24));
    }
}
