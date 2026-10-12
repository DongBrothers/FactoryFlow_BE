package com.factoryflow.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 활성화 링크 발송. ponytail: 운영은 SMTP 로 교체 예정(범위 밖). 토큰·이메일(개인정보)은 로그에 남기지 않는다(docs/specs 10.1). 로컬에서 메일
 * 서버 없이 토큰을 확인해야 하면 auth.activation.expose-token-in-log=true 로 켠다(로컬 전용).
 */
@Slf4j
@Component
public class ActivationNotifier {

    private final boolean exposeTokenInLog;

    public ActivationNotifier(
            @Value("${auth.activation.expose-token-in-log:false}") boolean exposeTokenInLog) {
        this.exposeTokenInLog = exposeTokenInLog;
    }

    public void sendActivationLink(String email, String token) {
        log.info("[auth] 활성화 링크 발송 요청 처리");
        if (exposeTokenInLog) {
            log.warn("[auth][local] 활성화 토큰(로컬 전용, 운영에서 끌 것) token={}", token);
        }
    }
}
