package com.factoryflow.mes.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.dto.response.EquipmentResponse;
import com.factoryflow.mes.service.EquipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "설비", description = "공정별 설비와 상태 (MES)")
@RestController
@RequestMapping("/mes/equipments")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentService equipmentService;

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
}
