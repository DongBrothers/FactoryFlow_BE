package com.factoryflow.auth.controller;

import com.factoryflow.auth.service.jwt.JwtProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개키를 JWKS 표준 형식으로 노출한다. gateway 등 검증 측이 이 공개키로 Access 토큰 서명을 검증한다. 표준 형식이어야 하므로 공통 ApiResponse 로
 * 감싸지 않는다.
 */
@Tag(name = "인증", description = "직원 계정 발급·활성화·로그인·토큰")
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtProvider jwtProvider;

    @Operation(
            summary = "공개키 조회 (JWKS)",
            description =
                    "Access 토큰 서명을 검증할 RSA 공개키를 JWKS 표준 형식으로 돌려준다. gateway 가 받아 캐시한다. 공통 응답으로 감싸지"
                            + " 않는다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "공개키 집합 ({\"keys\": [...]})")
    })
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwtProvider.publicJwkSet().toJSONObject();
    }
}
