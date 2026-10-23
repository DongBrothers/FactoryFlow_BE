package com.factoryflow.mes.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.mes.dto.response.LotVehicleResponse;
import com.factoryflow.mes.dto.response.VehiclePartLotResponse;
import com.factoryflow.mes.service.PartScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "부품 계보", description = "의장에서 찍은 부품 LOT 으로 차 ↔ LOT 을 찾는다 (리콜, MES)")
@RestController
@RequestMapping("/mes")
@RequiredArgsConstructor
public class PartScanController {

    /** 차대번호 17자 (I, O, Q 제외). */
    private static final String VIN = "[A-HJ-NPR-Z0-9]{17}";

    /** 찍는 LOT 번호와 같은 형식 (ScannedLotRequest). */
    private static final String LOT_NO = "LOT-\\S+";

    private final PartScanService partScanService;

    @Operation(
            summary = "차에 들어간 부품 LOT",
            description =
                    "의장 공정에서 찍은 LOT 을 부품 id 순으로 준다. 찍지 않은 부품은 나오지 않는다. 부품 이름은 inventory 부품 목록으로 붙인다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 찍은 것이 없으면 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "VIN 형식 오류"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "그 VIN 의 작업지시 없음 (WORK_ORDER_NOT_FOUND)")
    })
    @GetMapping("/vehicles/{vin}/part-lots")
    public ApiResponse<List<VehiclePartLotResponse>> findPartLotsOfVehicle(
            @Parameter(description = "차대번호", example = "KMH000013TU000012")
                    @PathVariable("vin")
                    @Pattern(regexp = VIN)
                    String vin) {
        List<VehiclePartLotResponse> partLots = partScanService.findPartLotsOfVehicle(vin);
        return ApiResponse.ok(partLots);
    }

    @Operation(
            summary = "LOT 이 들어간 차 (리콜)",
            description =
                    "그 LOT 을 의장에서 찍은 차를 찍은 시각 순으로 준다. 검사 전이라 VIN 이 아직 없는 차도 나온다. 출고 여부는 inventory 완성차 조회로 본다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 찍은 차가 없으면 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "lotNo 형식 오류 (LOT- 로 시작, 공백 없음, 32자 이하)")
    })
    @GetMapping("/part-lots/{lotNo}/vehicles")
    public ApiResponse<List<LotVehicleResponse>> findVehiclesOfLot(
            @Parameter(description = "LOT 번호", example = "LOT-261017-000012")
                    @PathVariable("lotNo")
                    @Pattern(regexp = LOT_NO)
                    @Size(max = 32)
                    String lotNo) {
        List<LotVehicleResponse> vehicles = partScanService.findVehiclesOfLot(lotNo);
        return ApiResponse.ok(vehicles);
    }
}
