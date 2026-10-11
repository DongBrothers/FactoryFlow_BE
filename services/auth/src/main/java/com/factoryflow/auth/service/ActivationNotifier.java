package com.factoryflow.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 활성화 링크 발송. ponytail: 로컬·데모는 링크(토큰)를 로그로 출력하는 스텁이다. 실제 SMTP 발송은 이번 범위 밖이며, 운영 profile 에서 교체한다 (로그로
 * 토큰을 남기지 않음).
 */
@Slf4j
@Component
public class ActivationNotifier {

    public void sendActivationLink(String email, String token) {
        log.info("[auth] 활성화 링크 발송(dev stub) 대상={} token={}", email, token);
    }
}
