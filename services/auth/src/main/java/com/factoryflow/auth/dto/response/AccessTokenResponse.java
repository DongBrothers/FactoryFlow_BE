package com.factoryflow.auth.dto.response;

import com.factoryflow.auth.service.IssuedTokens;
import io.swagger.v3.oas.annotations.media.Schema;

/** 로그인·재발급 응답 바디. Access 토큰만 담는다(Refresh 는 httpOnly 쿠키로 내린다). */
@Schema(description = "로그인·재발급 응답")
public record AccessTokenResponse(
        @Schema(
                        description = "Access 토큰 (JWT, RS256, 15분). Authorization: Bearer 로 보낸다",
                        example = "eyJraWQiOiJ...")
                String accessToken) {

    public static AccessTokenResponse from(IssuedTokens tokens) {
        return new AccessTokenResponse(tokens.accessToken());
    }
}
