package com.factoryflow.auth.service;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.UserAccount;
import com.factoryflow.auth.dto.request.ActivationRequest;
import com.factoryflow.auth.dto.request.IssueAccountRequest;
import com.factoryflow.auth.dto.response.IssuedAccountResponse;
import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.auth.repository.UserAccountRepository;
import com.factoryflow.common.web.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountRepository repository;
    private final ActivationTokenStore tokenStore;
    private final ActivationNotifier notifier;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    /** 관리자 발급: INVITED 계정 생성 후 활성화 토큰을 발행·발송한다. */
    @Transactional
    public IssuedAccountResponse issue(IssueAccountRequest request) {
        validateEmailDomain(request.email());
        if (repository.existsByEmployeeNo(request.employeeNo())) {
            throw new CustomException(AuthErrorCode.DUPLICATE_EMPLOYEE_NO);
        }
        if (repository.existsByEmail(request.email())) {
            throw new CustomException(AuthErrorCode.DUPLICATE_EMAIL);
        }

        UserAccount account =
                repository.save(
                        UserAccount.invite(
                                request.employeeNo(),
                                request.email(),
                                request.name(),
                                request.role()));
        String token = tokenStore.issue(account.getId());
        notifier.sendActivationLink(account.getEmail(), token);
        return IssuedAccountResponse.from(account);
    }

    /** 사원 활성화: 토큰을 소비해 계정을 찾고 비번 설정 후 ACTIVE 로 전환한다. */
    @Transactional
    public void activate(ActivationRequest request) {
        Long userId = tokenStore.consume(request.token());
        if (userId == null) {
            throw new CustomException(AuthErrorCode.INVALID_ACTIVATION_TOKEN);
        }
        UserAccount account =
                repository
                        .findById(userId)
                        .orElseThrow(
                                () -> new CustomException(AuthErrorCode.INVALID_ACTIVATION_TOKEN));
        account.activate(passwordEncoder.encode(request.password()));
    }

    private void validateEmailDomain(String email) {
        String domain = properties.allowedEmailDomain();
        if (domain != null && !email.endsWith("@" + domain)) {
            throw new CustomException(AuthErrorCode.INVALID_EMAIL_DOMAIN);
        }
    }
}
