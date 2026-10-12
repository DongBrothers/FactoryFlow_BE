package com.factoryflow.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 활성화 링크 발송. ponytail: 운영은 SMTP 로 교체 예정(범위 밖). 토큰·이메일(개인정보)은 로그에 남기지 않는다(docs/specs 10.1). 로컬에서 메일
 * 서버 없이 토큰을 확인해야 하면 Redis 에서 직접 조회한다(예: redis-cli KEYS "auth:activation:*").
 */
@Slf4j
@Component
public class ActivationNotifier {

    public void sendActivationLink(String email, String token) {
        log.info("[auth] 활성화 링크 발송 처리");
    }
}
