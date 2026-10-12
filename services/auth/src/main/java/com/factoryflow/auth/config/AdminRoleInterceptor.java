package com.factoryflow.auth.config;

import com.factoryflow.auth.domain.Role;
import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.common.web.exception.CustomException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자 전용 경로 가드(방어 심층). gateway 가 JWT 검증 후 넘겨주는 X-User-Role 헤더가 ADMIN 인지 확인한다. (서비스는 gateway 뒤에만
 * 노출되므로 이 헤더를 신뢰한다.) gateway 층의 역할 검사와 함께 이중으로 막는다. (docs/specs/auth-account.md)
 */
@Component
public class AdminRoleInterceptor implements HandlerInterceptor {

    private static final String ROLE_HEADER = "X-User-Role";

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!Role.ADMIN.name().equals(request.getHeader(ROLE_HEADER))) {
            throw new CustomException(AuthErrorCode.FORBIDDEN);
        }
        return true;
    }
}
