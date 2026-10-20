package com.factoryflow.production.dto.response;

import com.factoryflow.production.domain.Production;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "작업지시 발행 접수. 작업지시는 mes 가 이어서 만든다 (mes 작업지시 목록에서 본다)")
public record ReleaseWorkOrderResponse(
        @Schema(description = "생산 건 번호", example = "101") Long productionId,
        @Schema(description = "발행한 때", example = "2026-10-20T10:00:00") LocalDateTime releasedAt) {

    public static ReleaseWorkOrderResponse from(Production production) {
        return new ReleaseWorkOrderResponse(production.getId(), production.getReleasedAt());
    }
}
