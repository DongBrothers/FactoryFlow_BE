package com.factoryflow.auth.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.factoryflow.auth.config.ActivationMailProperties;
import com.factoryflow.auth.domain.PendingAccount;
import com.factoryflow.auth.domain.Role;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@ExtendWith(OutputCaptureExtension.class)
class ActivationNotifierTest {

    static {
        // GreenMail(테스트 도구)은 받은 메일의 주소를 INFO 로 남긴다. 우리 코드의 로그만 검사하도록 끈다
        LoggingSystem.get(ActivationNotifierTest.class.getClassLoader())
                .setLogLevel("com.icegreen", LogLevel.WARN);
    }

    /** 가짜 SMTP 서버. 실제 메일을 보내지 않고 받은 메일을 꺼내 볼 수 있다. */
    @RegisterExtension
    static final GreenMailExtension mail = new GreenMailExtension(ServerSetupTest.SMTP);

    private final ActivationNotifier notifier =
            new ActivationNotifier(
                    mailSender(),
                    new ActivationMailProperties(
                            "http://localhost:5173/activate", "noreply@factoryflow.local"));

    private static JavaMailSenderImpl mailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(ServerSetupTest.SMTP.getPort());
        return sender;
    }

    @Test
    void doesNotLogTokenOrEmail(CapturedOutput output) {
        notifier.sendActivationLink(
                new PendingAccount("hong@gmail.com", "홍길동", Role.SALES),
                "secret-activation-token-123");

        assertThat(mail.getReceivedMessages()).hasSize(1);
        // 토큰(자격증명)·이메일(개인정보)은 로그에 남지 않는다 (docs/specs 10.1)
        assertThat(output).doesNotContain("secret-activation-token-123");
        assertThat(output).doesNotContain("hong@gmail.com");
    }
}
