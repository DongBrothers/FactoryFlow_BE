package com.factoryflow.auth.service.jwt;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.UserAccount;
import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.common.web.exception.CustomException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Access 토큰(JWT, RS256)을 개인키로 서명하고, 공개키를 JWKS 로 노출한다. 검증 측(gateway)은 공개키만 가진다. 키쌍은 AuthConfig 가
 * 만든다. (docs/specs/auth-account.md)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtProvider {

    private final AuthProperties properties;
    private final RSAKey rsaKey;

    /**
     * claim 은 sub(public_id), role(역할 코드) 만 넣는다. 권한은 넣지 않는다 — gateway 는 Redis 로 받은 역할 → 권한 표로 판단한다
     * (docs/specs/role-permission-admin.md). 이메일 같은 개인정보는 넣지 않는다.
     */
    public String createAccessToken(UserAccount account, String role) {
        Instant now = Instant.now();
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .subject(account.getPublicId())
                        .claim("role", role)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(properties.accessTokenTtl())))
                        .build();
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                        claims);
        try {
            jwt.sign(new RSASSASigner(rsaKey));
        } catch (JOSEException e) {
            // 원인(키 오류 등)은 남기고, 토큰·claim 값은 남기지 않는다
            log.error("[auth] Access 토큰 서명 실패", e);
            throw new CustomException(AuthErrorCode.TOKEN_SIGNING_FAILED);
        }
        return jwt.serialize();
    }

    /** 공개키 JWK 집합. 내부 API /internal/jwks 로 gateway 에 준다. */
    public JWKSet publicJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK());
    }
}
