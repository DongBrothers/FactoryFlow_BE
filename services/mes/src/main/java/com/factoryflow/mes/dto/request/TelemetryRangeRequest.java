package com.factoryflow.mes.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

@Schema(description = "센서값 조회 시간 범위 [from, to). 최대 1시간")
@TelemetryRange
public record TelemetryRangeRequest(
        @Schema(description = "시작 시각 (포함)", example = "2026-10-21T10:00:00")
                @NotNull(message = "시작 시각을 넣어야 합니다.")
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                LocalDateTime from,
        @Schema(description = "끝 시각 (제외)", example = "2026-10-21T10:30:00")
                @NotNull(message = "끝 시각을 넣어야 합니다.")
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                LocalDateTime to) {}
