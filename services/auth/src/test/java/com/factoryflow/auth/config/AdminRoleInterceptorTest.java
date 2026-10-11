package com.factoryflow.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.factoryflow.auth.exception.AuthErrorCode;
import com.factoryflow.common.web.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminRoleInterceptorTest {

    private final AdminRoleInterceptor interceptor = new AdminRoleInterceptor();

    @Test
    void allowsAdminRole() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Role", "ADMIN");

        boolean result =
                interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
    }

    @Test
    void rejectsNonAdminRole() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Role", "WORKER");

        assertThatThrownBy(
                        () ->
                                interceptor.preHandle(
                                        request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.FORBIDDEN);
    }

    @Test
    void rejectsMissingRoleHeader() {
        assertThatThrownBy(
                        () ->
                                interceptor.preHandle(
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse(),
                                        new Object()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.FORBIDDEN);
    }
}
