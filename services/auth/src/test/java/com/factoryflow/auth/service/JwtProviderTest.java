package com.factoryflow.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.Role;
import com.factoryflow.auth.domain.UserAccount;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtProviderTest {

    private final AuthProperties properties =
            new AuthProperties(
                    "gmail.com",
                    Duration.ofHours(24),
                    Duration.ofMinutes(15),
                    Duration.ofDays(14),
                    5,
                    Duration.ofMinutes(15));
    private final JwtProvider provider = new JwtProvider(properties);

    @Test
    void accessTokenIsSignedWithPublicKeyAndCarriesClaims() throws Exception {
        var account = UserAccount.invite("E1001", "a@gmail.com", "홍길동", Role.WORKER);
        ReflectionTestUtils.setField(account, "id", 7L);

        String token = provider.createAccessToken(account);

        SignedJWT jwt = SignedJWT.parse(token);
        RSAKey publicKey = provider.publicJwkSet().getKeys().get(0).toRSAKey();
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(account.getPublicId());
        assertThat(claims.getStringClaim("employeeNo")).isEqualTo("E1001");
        assertThat(claims.getStringClaim("role")).isEqualTo("WORKER");
        assertThat(claims.getExpirationTime()).isAfter(new Date());
    }

    @Test
    void publicJwkSetDoesNotExposePrivateKey() {
        assertThat(provider.publicJwkSet().getKeys().get(0).isPrivate()).isFalse();
    }
}
