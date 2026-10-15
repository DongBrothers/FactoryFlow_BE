package com.factoryflow.auth.service.mail;

import com.factoryflow.auth.config.ActivationMailProperties;
import com.factoryflow.auth.domain.PendingAccount;
import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.common.web.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 활성화 메일 발송. 링크는 프론트의 활성화 페이지 주소에 ?token= 을 붙인다. 보내지 못하면 MAIL_UNAVAILABLE(503). 토큰·이메일(개인정보)·링크는
 * 로그에 남기지 않는다. (docs/specs/auth-account.md "활성화 메일")
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivationNotifier {

    static final String SUBJECT = "[FactoryFlow] 계정 활성화 안내";

    private final JavaMailSender mailSender;
    private final ActivationMailProperties properties;

    public void sendActivationLink(PendingAccount pending, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(pending.email());
        message.setSubject(SUBJECT);
        message.setText(body(pending.name(), properties.linkBaseUrl() + "?token=" + token));
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // 예외 메시지에 받는 사람 주소가 들어갈 수 있어 예외 종류만 남긴다
            log.warn("[auth] 활성화 메일 발송 실패: {}", e.getClass().getSimpleName());
            throw new CustomException(AuthErrorCode.MAIL_UNAVAILABLE);
        }
        log.info("[auth] 활성화 메일 발송");
    }

    private static String body(String name, String link) {
        return name
                + "님, FactoryFlow 계정이 발급되었습니다.\n\n"
                + "아래 링크에서 비밀번호를 설정하면 계정이 활성화됩니다.\n"
                + link
                + "\n\n링크는 24시간 동안 유효합니다. 지나면 관리자에게 다시 발급을 요청해 주세요.\n";
    }
}
