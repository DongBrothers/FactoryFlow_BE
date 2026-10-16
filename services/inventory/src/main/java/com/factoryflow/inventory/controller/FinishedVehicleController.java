package com.factoryflow.inventory.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.inventory.dto.response.ShipFinishedVehicleResponse;
import com.factoryflow.inventory.dto.response.VehiclePartLotResponse;
import com.factoryflow.inventory.service.FinishedVehicleService;
import com.factoryflow.inventory.service.PartLotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "완성차", description = "완성차 재고, 출고, 쓰인 부품 LOT")
@RestController
@RequestMapping("/inventory/finished-vehicles")
@RequiredArgsConstructor
public class FinishedVehicleController {

    /** 차대번호 형식: 17자, 영문 대문자·숫자, I·O·Q 없음. */
    private static final String VIN = "[A-HJ-NPR-Z0-9]{17}";

    private final FinishedVehicleService finishedVehicleService;
    private final PartLotService partLotService;

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

    @Operation(
            summary = "차에 쓰인 부품 LOT",
            description =
                    "완성차 한 대에 들어간 부품이 어느 LOT 에서 나왔는지 본다. 부품 코드 순이고, LOT 번호가 없으면 LOT 을 붙이기 전부터 있던 재고에서 썼다는 뜻이다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 이 기능 전에 완성된 차는 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "vin 이 차대번호 형식이 아님 (17자, 영문 대문자·숫자, I·O·Q 없음)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "완성차 없음 (FINISHED_VEHICLE_NOT_FOUND)")
    })
    @GetMapping("/{vin}/part-lots")
    public ApiResponse<List<VehiclePartLotResponse>> findPartLots(
            @Parameter(description = "차대번호", example = "KMH000017TU000012")
                    @PathVariable("vin")
                    @Pattern(regexp = VIN)
                    String vin) {
        List<VehiclePartLotResponse> partLots = partLotService.findPartLotsOfVehicle(vin);
        return ApiResponse.ok(partLots);
    }
}
