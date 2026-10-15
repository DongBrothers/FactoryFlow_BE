package com.factoryflow.auth.controller;

import com.factoryflow.auth.service.jwt.JwtProvider;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개키를 JWKS 표준 형식으로 내준다. gateway 의 Spring Security 가 jwk-set-uri 로 받아 Access 토큰 서명을 검증한다. 서버끼리만 쓰는
 * 내부 API 라 gateway 에 노출하지 않는다. Spring Security 가 표준 형식({"keys": [...]})으로만 읽으므로 공통 ApiResponse 로
 * 감싸지 않는다.
 */
@RestController
@RequestMapping("/internal/jwks")
@RequiredArgsConstructor
public class InternalJwksController {

    private final JwtProvider jwtProvider;

    @GetMapping
    public Map<String, Object> jwks() {
        return jwtProvider.publicJwkSet().toJSONObject();
    }
}
