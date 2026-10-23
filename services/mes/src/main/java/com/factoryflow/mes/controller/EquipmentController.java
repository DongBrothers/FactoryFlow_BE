package com.factoryflow.mes.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.dto.request.TelemetryRangeRequest;
import com.factoryflow.mes.dto.response.EquipmentResponse;
import com.factoryflow.mes.dto.response.EquipmentStatusHistoryResponse;
import com.factoryflow.mes.dto.response.EquipmentTelemetryResponse;
import com.factoryflow.mes.service.EquipmentService;
import com.factoryflow.mes.service.TelemetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "설비", description = "공정별 설비와 상태 (MES)")
@RestController
@RequestMapping("/mes/equipments")
@RequiredArgsConstructor
public class EquipmentController {

    /** 설비 코드 형식: {공정}-{두 자리 번호} (예: PRESS-01). */
    private static final String EQUIPMENT_CODE = "[A-Z]+-\\d{2}";

    private final EquipmentService equipmentService;
    private final TelemetryService telemetryService;

    @Operation(
            summary = "공정의 설비 목록",
            description =
                    "한 공정의 설비와 상태를 설비 코드 순으로 돌려준다. 작업자가 공정을 시작할 설비를 고를 때 쓴다 (RUNNING 인 설비만 시작 가능).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "process 가 없거나 PRESS, BODY, PAINT, ASSEMBLY, INSPECTION 이 아님")
    })
    @GetMapping
    public ApiResponse<List<EquipmentResponse>> findEquipments(
            @Parameter(description = "공정", example = "PRESS") @RequestParam("process")
                    ProductionProcess process) {
        List<EquipmentResponse> equipments = equipmentService.findEquipments(process);
        return ApiResponse.ok(equipments);
    }

    @Operation(
            summary = "설비 상태 이력",
            description =
                    "설비 하나의 상태 변경 이력(RUNNING, STOPPED, FAULT)을 최신 먼저 id 커서로 나눠 준다. 처음엔 lastId 없이, 다음 페이지는"
                            + " nextCursor 를 lastId 로 넘긴다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 이력이 없으면 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "설비 코드 형식 오류, lastId 가 양수가 아님, size 1~100 밖"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "설비 없음 (EQUIPMENT_NOT_FOUND)")
    })
    @GetMapping("/{equipmentCode}/history")
    public ApiResponse<CursorPage<EquipmentStatusHistoryResponse>> findEquipmentHistory(
            @Parameter(description = "설비 코드", example = "PRESS-01")
                    @PathVariable("equipmentCode")
                    @Pattern(regexp = EQUIPMENT_CODE)
                    String equipmentCode,
            @Parameter(description = "이 id 보다 앞(오래된 것)부터. 처음이면 비운다", example = "12")
                    @RequestParam(value = "lastId", required = false)
                    @Positive
                    Long lastId,
            @Parameter(description = "한 번에 받을 개수 (1~100)", example = "20")
                    @RequestParam(value = "size", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size) {
        CursorPage<EquipmentStatusHistoryResponse> history =
                equipmentService.findHistory(equipmentCode, lastId, size);
        return ApiResponse.ok(history);
    }

    @Operation(
            summary = "설비 센서값",
            description =
                    "설비 하나가 잰 값을 시간 범위 [from, to) 로 본다 (최대 1시간). 값마다 그때 하던 작업지시와 배정 회차를 함께 준다. 항목마다 정상 범위(기준선)를 함께 준다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 값이 없으면 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "설비 코드 형식 오류, from·to 없음 또는 시각 형식 오류, from 이 to 보다 앞이 아님, 범위가 1시간 초과"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "설비 없음 (EQUIPMENT_NOT_FOUND)")
    })
    @GetMapping("/{equipmentCode}/telemetry")
    public ApiResponse<EquipmentTelemetryResponse> findEquipmentTelemetry(
            @Parameter(description = "설비 코드", example = "PRESS-01")
                    @PathVariable("equipmentCode")
                    @Pattern(regexp = EQUIPMENT_CODE)
                    String equipmentCode,
            @Valid @ModelAttribute TelemetryRangeRequest range) {
        EquipmentTelemetryResponse telemetry =
                telemetryService.findEquipmentTelemetry(equipmentCode, range.from(), range.to());
        return ApiResponse.ok(telemetry);
    }
}
