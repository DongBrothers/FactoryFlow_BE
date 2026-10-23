package com.factoryflow.mes.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.common.web.response.CursorPage;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.dto.request.CompleteWorkOrderStepRequest;
import com.factoryflow.mes.dto.request.RobotProcess;
import com.factoryflow.mes.dto.request.StartWorkOrderStepRequest;
import com.factoryflow.mes.dto.response.StepTelemetryResponse;
import com.factoryflow.mes.dto.response.WorkOrderDetailResponse;
import com.factoryflow.mes.dto.response.WorkOrderResponse;
import com.factoryflow.mes.service.TelemetryService;
import com.factoryflow.mes.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "작업지시", description = "MES 작업지시·공정·실적. 라인 작업자가 공정을 순서대로 시작·종료한다")
@RestController
@RequestMapping("/mes/work-orders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;
    private final TelemetryService telemetryService;

    @Operation(
            summary = "작업지시 목록",
            description =
                    "RELEASED(첫 공정 대기) 또는 IN_PROGRESS 작업지시를 id 커서(lastId, size)로 나눠 준다. 항목마다 지금 할 공정을 함께 준다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "status 가 없거나 RELEASED, IN_PROGRESS 가 아님, lastId 음수, size 1~100 밖")
    })
    @GetMapping
    public ApiResponse<CursorPage<WorkOrderResponse>> findWorkOrders(
            @Parameter(description = "작업지시 상태 (RELEASED, IN_PROGRESS)", example = "RELEASED")
                    @RequestParam(value = "status", required = false)
                    @NotNull
                    @Pattern(regexp = "RELEASED|IN_PROGRESS")
                    String status,
            @Parameter(description = "이 id 다음부터 (처음이면 0)", example = "0")
                    @RequestParam(value = "lastId", defaultValue = "0")
                    @PositiveOrZero
                    Long lastId,
            @Parameter(description = "한 번에 받을 개수 (1~100)", example = "20")
                    @RequestParam(value = "size", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size) {
        CursorPage<WorkOrderResponse> workOrders =
                workOrderService.findWorkOrders(status, lastId, size);
        return ApiResponse.ok(workOrders);
    }

    @Operation(summary = "작업지시 상세", description = "작업지시와 공정 5개(라우팅 순서), 공정별 실적을 돌려준다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "workOrderId 가 양수가 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "작업지시 없음 (WORK_ORDER_NOT_FOUND)")
    })
    @GetMapping("/{workOrderId}")
    public ApiResponse<WorkOrderDetailResponse> findWorkOrder(
            @Parameter(description = "작업지시 번호", example = "12")
                    @PathVariable("workOrderId")
                    @Positive
                    Long workOrderId) {
        WorkOrderDetailResponse workOrder = workOrderService.findWorkOrder(workOrderId);
        return ApiResponse.ok(workOrder);
    }

    @Operation(
            summary = "공정 시작",
            description =
                    "설비를 지정해 공정을 시작한다 (사람 공정, 로봇 공정 수동 개입). 앞 공정이 모두 끝났고 이 공정이 시작 전이며, 설비가 이 공정의 것이고 가동 중이며 비어 있을 때만 된다. 로봇 공정이 설비에 배정된(DISPATCHED) 상태면 설비 보고를 기다린다. 첫 공정(PRESS)을 시작하면 생산 건이 IN_PROGRESS 가 되고 이후 취소할 수 없다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "시작함. 작업지시 상세를 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "workOrderId 가 양수가 아님, process 가 정해진 값이 아님, equipmentCode 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "작업지시 없음 (WORK_ORDER_NOT_FOUND), 설비 없음 (EQUIPMENT_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "앞 공정이 안 끝남 (STEP_OUT_OF_ORDER), 시작 전 공정이 아님 (INVALID_STEP_STATUS), 다른 공정의 설비 (EQUIPMENT_PROCESS_MISMATCH), 설비가 가동 중이 아님 (EQUIPMENT_NOT_RUNNING), 설비가 다른 차 작업 중 (EQUIPMENT_BUSY), 취소된 작업지시 (WORK_ORDER_CLOSED), 다른 작업이 먼저 바꿈 (MES_CONFLICT)")
    })
    @PostMapping("/{workOrderId}/steps/{process}/start")
    public ApiResponse<WorkOrderDetailResponse> startStep(
            @Parameter(description = "작업지시 번호", example = "12")
                    @PathVariable("workOrderId")
                    @Positive
                    Long workOrderId,
            @Parameter(description = "공정", example = "PRESS") @PathVariable("process")
                    ProductionProcess process,
            @Valid @RequestBody StartWorkOrderStepRequest request) {
        WorkOrderDetailResponse workOrder =
                workOrderService.startStep(workOrderId, process, request.equipmentCode());
        return ApiResponse.ok(workOrder);
    }

    @Operation(
            summary = "공정 종료 (실적 입력)",
            description =
                    "진행 중인 공정을 끝내고 실적을 남긴다. 양품(GOOD)이면 공정이 끝나고 다음 공정을 시작할 수 있다. 불량(DEFECT, 사유 필수)이면 실적만 남기고 같은 공정을 다시 한다. 의장(ASSEMBLY) 양품이면 찍은 부품 LOT(scannedLots)을 함께 남길 수 있다. 작업자는 로그인한 직원(X-User-Id)이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "실적을 남김. 작업지시 상세를 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "X-User-Id 없음, result 없음 또는 정해진 값이 아님, DEFECT 인데 사유 없음, process 가 정해진 값이 아님, scannedLots 형식(같은 부품 두 번, LOT- 아님, 51개 이상), 의장 양품이 아닌데 scannedLots (SCAN_NOT_ALLOWED)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "작업지시 없음 (WORK_ORDER_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "진행 중인 공정이 아님 (INVALID_STEP_STATUS), 취소된 작업지시 (WORK_ORDER_CLOSED), 다른 작업이 먼저 바꿈 (MES_CONFLICT)")
    })
    @PostMapping("/{workOrderId}/steps/{process}/complete")
    public ApiResponse<WorkOrderDetailResponse> completeStep(
            @Parameter(
                            description = "로그인한 직원 public_id (gateway 가 넣음)",
                            example = "3f2a9c1e-0000-4000-8000-000000000001")
                    @RequestHeader("X-User-Id")
                    @NotBlank
                    String workerId,
            @Parameter(description = "작업지시 번호", example = "12")
                    @PathVariable("workOrderId")
                    @Positive
                    Long workOrderId,
            @Parameter(description = "공정", example = "PRESS") @PathVariable("process")
                    ProductionProcess process,
            @Valid @RequestBody CompleteWorkOrderStepRequest request) {
        WorkOrderDetailResponse workOrder =
                workOrderService.completeStep(
                        workOrderId,
                        process,
                        request.result(),
                        request.defectReason(),
                        request.scannedLotsOrEmpty(),
                        workerId);
        return ApiResponse.ok(workOrder);
    }

    @Operation(
            summary = "공정 센서값",
            description =
                    "로봇 공정(PRESS, BODY, PAINT)을 하는 동안 설비가 잰 값을 시간 순으로 본다. 재작업했으면 배정 회차별로 나눠 준다. 항목마다 정상 범위(기준선)를 함께 준다. 값은 90일 보관한다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 값이 없으면 runs 가 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "workOrderId 가 양수가 아님, process 가 로봇 공정(PRESS, BODY, PAINT)이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "작업지시 없음 (WORK_ORDER_NOT_FOUND)")
    })
    @GetMapping("/{workOrderId}/steps/{process}/telemetry")
    public ApiResponse<StepTelemetryResponse> findStepTelemetry(
            @Parameter(description = "작업지시 번호", example = "12")
                    @PathVariable("workOrderId")
                    @Positive
                    Long workOrderId,
            @Parameter(description = "로봇 공정", example = "PRESS")
                    @PathVariable("process")
                    @RobotProcess
                    ProductionProcess process) {
        StepTelemetryResponse telemetry = telemetryService.findStepTelemetry(workOrderId, process);
        return ApiResponse.ok(telemetry);
    }
}
