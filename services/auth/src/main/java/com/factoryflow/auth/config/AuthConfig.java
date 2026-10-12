package com.factoryflow.auth.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, CookieProperties.class})
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Access 토큰 서명용 RSA 키쌍.
     *
     * <p>ponytail: 기동 시 1회 생성한다. 재시작하면 키가 바뀌어 기존 토큰이 무효가 된다(로컬·데모용). 운영에서는 Secrets Manager 등에서 고정
     * 키를 로드하도록 교체한다.
     */
    @Bean
    public RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("RSA 키 생성 실패", e);
        }
    }
}
