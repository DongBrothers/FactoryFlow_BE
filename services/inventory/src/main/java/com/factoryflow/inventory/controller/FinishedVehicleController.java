package com.factoryflow.inventory.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.inventory.dto.response.FinishedVehicleListResponse;
import com.factoryflow.inventory.dto.response.ShipFinishedVehicleResponse;
import com.factoryflow.inventory.service.FinishedVehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "완성차", description = "완성차 재고 목록, 출고, 쓰인 부품 LOT")
@RestController
@RequestMapping("/inventory/finished-vehicles")
@RequiredArgsConstructor
public class FinishedVehicleController {

    /** 차대번호 형식: 17자, 영문 대문자·숫자, I·O·Q 없음. */
    private static final String VIN = "[A-HJ-NPR-Z0-9]{17}";

    private final FinishedVehicleService finishedVehicleService;

    @Operation(
            summary = "완성차 목록",
            description =
                    "완성차 재고를 최신 먼저 본다. status 로 창고에 있는 차(IN_STOCK)나 출고된 차(SHIPPED)만 거를 수 있다. 다음 페이지는 응답의 nextVin 을 lastVin 으로 넘긴다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 완성차가 없으면 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "status 가 IN_STOCK·SHIPPED 가 아님, lastVin 이 차대번호 형식이 아님, size 1~100 밖"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "lastVin 완성차 없음 (FINISHED_VEHICLE_NOT_FOUND)")
    })
    @GetMapping
    public ApiResponse<FinishedVehicleListResponse> findFinishedVehicles(
            @Parameter(description = "완성차 상태 (IN_STOCK, SHIPPED). 없으면 전체", example = "IN_STOCK")
                    @RequestParam(value = "status", required = false)
                    @Pattern(regexp = "IN_STOCK|SHIPPED")
                    String status,
            @Parameter(description = "이 차보다 오래된 것부터 (처음이면 없음)", example = "KMH000017TU000012")
                    @RequestParam(value = "lastVin", required = false)
                    @Pattern(regexp = VIN)
                    String lastVin,
            @Parameter(description = "한 번에 받을 개수 (1~100)", example = "20")
                    @RequestParam(value = "size", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int size) {
        FinishedVehicleListResponse vehicles =
                finishedVehicleService.findFinishedVehicles(status, lastVin, size);
        return ApiResponse.ok(vehicles);
    }

    @Operation(
            summary = "완성차 출고",
            description =
                    "창고에 있는 완성차 한 대를 출고한다. 완성차가 출고됨(SHIPPED)이 되고 shipment.completed 를 발행해, 주문의 그 차량도 출고됨이 된다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "출고됨. 출고한 완성차를 돌려준다"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "vin 이 차대번호 형식이 아님 (17자, 영문 대문자·숫자, I·O·Q 없음)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "완성차 없음 (FINISHED_VEHICLE_NOT_FOUND)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "이미 출고됨 (FINISHED_VEHICLE_ALREADY_SHIPPED), 다른 작업이 먼저 바꿈 (FINISHED_VEHICLE_CONFLICT)")
    })
    @PostMapping("/{vin}/ship")
    public ApiResponse<ShipFinishedVehicleResponse> ship(
            @Parameter(description = "차대번호", example = "KMH000017TU000012")
                    @PathVariable("vin")
                    @Pattern(regexp = VIN)
                    String vin) {
        ShipFinishedVehicleResponse vehicle = finishedVehicleService.ship(vin);
        return ApiResponse.ok(vehicle);
    }
}
