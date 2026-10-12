package com.factoryflow.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class RefreshCookieFactoryTest {

    private final AuthProperties authProperties =
            new AuthProperties(
                    "gmail.com",
                    Duration.ofHours(24),
                    Duration.ofMinutes(15),
                    Duration.ofDays(14),
                    5,
                    Duration.ofMinutes(15));
    private final RefreshCookieFactory factory =
            new RefreshCookieFactory(authProperties, new CookieProperties(true, "Lax"));

    @Test
    void createsHttpOnlySecureScopedCookie() {
        ResponseCookie cookie = factory.create("tok-123");

        assertThat(cookie.getName()).isEqualTo("refreshToken");
        assertThat(cookie.getValue()).isEqualTo("tok-123");
        assertThat(cookie.isHttpOnly()).isTrue(); // JS 접근 차단 (XSS 방어)
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void expiredCookieClearsValueAndMaxAge() {
        ResponseCookie cookie = factory.expired();

        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
    }
}
