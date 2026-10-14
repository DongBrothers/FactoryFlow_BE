package com.factoryflow.auth.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ActivationNotifierTest {

    private final ActivationNotifier notifier = new ActivationNotifier();

    @Test
    void doesNotLogTokenOrEmail(CapturedOutput output) {
        notifier.sendActivationLink("hong@gmail.com", "secret-activation-token-123");

        // 토큰(자격증명)·이메일(개인정보)은 로그에 남지 않는다 (docs/specs 10.1)
        assertThat(output).doesNotContain("secret-activation-token-123");
        assertThat(output).doesNotContain("hong@gmail.com");
    }
}
