package com.factoryflow.auth.service;

import com.factoryflow.auth.config.AuthProperties;
import com.factoryflow.auth.domain.AccountStatus;
import com.factoryflow.auth.domain.PendingAccount;
import com.factoryflow.auth.domain.UserAccount;
import com.factoryflow.auth.dto.request.ActivateAccountRequest;
import com.factoryflow.auth.dto.request.IssueAccountRequest;
import com.factoryflow.auth.dto.request.LoginRequest;
import com.factoryflow.auth.dto.response.IssueAccountResponse;
import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.auth.repository.UserAccountRepository;
import com.factoryflow.common.web.exception.CustomException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 계정 발급·활성화·로그인·토큰 (docs/specs/auth-account.md). */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountRepository repository;
    private final ActivationTokenStore activationTokenStore;
    private final ActivationNotifier notifier;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginAttemptGuard loginAttemptGuard;

    /** 관리자 발급: DB 에 저장하지 않고 Redis 에 대기시킨 뒤 활성화 링크를 보낸다. 같은 이메일 대기 건은 덮어쓴다. */
    public IssueAccountResponse issue(IssueAccountRequest request) {
        String email = normalize(request.email());
        validateEmailDomain(email);
        if (repository.existsByEmail(email)) {
            throw new CustomException(AuthErrorCode.DUPLICATE_EMAIL);
        }
        var pending = new PendingAccount(email, request.name(), request.role());
        String token = activationTokenStore.issue(pending);
        notifier.sendActivationLink(email, token);
        return IssueAccountResponse.from(pending);
    }

    /**
     * 직원 활성화: 토큰을 소비해 대기 정보를 꺼내고, 비번을 설정한 ACTIVE 계정으로 처음 저장한다. 트랜잭션을 걸지 않는다 — save 한 번뿐이고, 유니크 위반을
     * 여기서 잡아 409 로 바꾸려면 바깥 트랜잭션이 rollback-only 로 표시되면 안 된다.
     */
    public void activate(ActivateAccountRequest request) {
        PendingAccount pending = activationTokenStore.consume(request.token());
        if (pending == null) {
            throw new CustomException(AuthErrorCode.INVALID_ACTIVATION_TOKEN);
        }
        if (repository.existsByEmail(pending.email())) {
            throw new CustomException(AuthErrorCode.DUPLICATE_EMAIL);
        }
        try {
            repository.save(
                    UserAccount.create(
                            pending.email(),
                            pending.name(),
                            pending.role(),
                            passwordEncoder.encode(request.password())));
        } catch (DataIntegrityViolationException e) {
            // 확인과 저장 사이에 같은 이메일이 먼저 저장된 경우 (거의 동시의 두 번째 발급)만 409. 그 밖의 무결성 오류는 그대로 던진다
            if (repository.existsByEmail(pending.email())) {
                throw new CustomException(AuthErrorCode.DUPLICATE_EMAIL);
            }
            throw e;
        }
    }

    /**
     * 로그인: 이메일+비번 검증 후 Access/Refresh 발급. 실패는 세어 임계치 초과 시 잠근다. 트랜잭션을 걸지 않는다 — 느린 bcrypt 검증 동안 DB
     * 커넥션을 쥐지 않도록 조회만 repository 에 맡긴다.
     */
    public IssuedTokens login(LoginRequest request) {
        String email = normalize(request.email());
        if (loginAttemptGuard.isLocked(email)) {
            throw new CustomException(AuthErrorCode.ACCOUNT_LOCKED);
        }
        UserAccount account = repository.findByEmail(email).orElse(null);
        if (account == null
                || !passwordEncoder.matches(request.password(), account.getPassword())) {
            loginAttemptGuard.recordFailure(email);
            throw new CustomException(AuthErrorCode.LOGIN_FAILED);
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new CustomException(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        loginAttemptGuard.reset(email);
        return issueTokens(account);
    }

    /** 재발급: Refresh(쿠키에서 읽음)를 소비(회전)하고 새 Access/Refresh 를 발급한다. 조회 한 번뿐이라 트랜잭션을 걸지 않는다. */
    public IssuedTokens refresh(String refreshToken) {
        if (refreshToken == null) {
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        String publicId = refreshTokenStore.consume(refreshToken);
        if (publicId == null) {
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        UserAccount account =
                repository
                        .findByPublicId(publicId)
                        .orElseThrow(
                                () -> new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new CustomException(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        return issueTokens(account);
    }

    /** 로그아웃: Refresh 토큰(쿠키에서 읽음)을 폐기한다. */
    public void logout(String refreshToken) {
        if (refreshToken != null) {
            refreshTokenStore.revoke(refreshToken);
        }
    }

    private IssuedTokens issueTokens(UserAccount account) {
        String accessToken = jwtProvider.createAccessToken(account);
        String refreshToken = refreshTokenStore.issue(account.getPublicId());
        return new IssuedTokens(accessToken, refreshToken);
    }

    private void validateEmailDomain(String email) {
        String domain = properties.allowedEmailDomain();
        if (domain != null && !email.endsWith("@" + domain.toLowerCase(Locale.ROOT))) {
            throw new CustomException(AuthErrorCode.INVALID_EMAIL_DOMAIN);
        }
    }

    /** 이메일은 앞뒤 공백 제거 + 소문자로 저장·비교한다. */
    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
