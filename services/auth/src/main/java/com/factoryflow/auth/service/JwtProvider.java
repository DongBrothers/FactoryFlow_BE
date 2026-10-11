package com.factoryflow.auth.service;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.UserAccount;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Access 토큰(JWT, RS256)을 개인키로 서명하고, 공개키를 JWKS 로 노출한다. 검증 측(gateway)은 공개키만 가진다.
 *
 * <p>ponytail: RSA 키쌍을 기동 시 1회 생성한다. 재시작하면 키가 바뀌어 기존 토큰이 무효가 된다(로컬·데모용). 운영에서는 Secrets Manager 등에서
 * 고정 키를 로드하도록 교체한다. (docs/specs/auth-account.md)
 */
@Component
public class JwtProvider {

    private final AuthProperties properties;
    private final RSAKey rsaKey;
    private final JWSSigner signer;

    public JwtProvider(AuthProperties properties) {
        this.properties = properties;
        try {
            this.rsaKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            this.signer = new RSASSASigner(rsaKey);
        } catch (JOSEException e) {
            throw new IllegalStateException("RSA 키 생성 실패", e);
        }
    }

    public String createAccessToken(UserAccount account) {
        Instant now = Instant.now();
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .subject(String.valueOf(account.getId()))
                        .claim("employeeNo", account.getEmployeeNo())
                        .claim("role", account.getRole().name())
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(properties.accessTokenTtl())))
                        .build();
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                        claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("토큰 서명 실패", e);
        }
        return jwt.serialize();
    }

    /** 공개키 JWK 집합. /.well-known/jwks.json 으로 노출된다. */
    public JWKSet publicJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK());
    }
}
