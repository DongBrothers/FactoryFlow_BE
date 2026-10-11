package com.factoryflow.auth.controller;

import com.factoryflow.auth.service.JwtProvider;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개키를 JWKS 표준 형식으로 노출한다. gateway 등 검증 측이 이 공개키로 Access 토큰 서명을 검증한다. 표준 형식이어야 하므로 공통 ApiResponse 로
 * 감싸지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtProvider jwtProvider;

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwtProvider.publicJwkSet().toJSONObject();
    }
}
