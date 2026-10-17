package com.factoryflow.auth.service.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.Role;
import com.factoryflow.auth.domain.UserAccount;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private final AuthProperties properties =
            new AuthProperties(
                    "gmail.com",
                    Duration.ofHours(24),
                    Duration.ofMinutes(15),
                    Duration.ofDays(14),
                    5,
                    Duration.ofMinutes(15));
    private final JwtProvider provider = new JwtProvider(properties, rsaKey());

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void accessTokenIsSignedWithPublicKeyAndCarriesClaims() throws Exception {
        var account = UserAccount.create("a@gmail.com", "홍길동", Role.PRODUCTION, "hash");

        String token = provider.createAccessToken(account);

        SignedJWT jwt = SignedJWT.parse(token);
        RSAKey publicKey = provider.publicJwkSet().getKeys().get(0).toRSAKey();
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(account.getPublicId());
        assertThat(claims.getStringClaim("role")).isEqualTo("PRODUCTION");
        assertThat(claims.getExpirationTime()).isAfter(new Date());
        // 이메일(개인정보)은 토큰에 넣지 않는다
        assertThat(claims.getClaims()).doesNotContainKeys("email", "employeeNo");
    }

    @Test
    void publicJwkSetDoesNotExposePrivateKey() {
        assertThat(provider.publicJwkSet().getKeys().get(0).isPrivate()).isFalse();
    }
}
