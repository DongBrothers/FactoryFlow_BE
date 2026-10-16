package com.factoryflow.inventory.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.inventory.dto.response.LotVehicleResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "부품 LOT", description = "부품 LOT 추적")
@RestController
@RequestMapping("/inventory/part-lots")
@RequiredArgsConstructor
public class PartLotController {

    /** LOT 번호 형식: LOT-{입고일 yyMMdd}-{구매 건 6자리}. */
    private static final String LOT_NO = "LOT-\\d{6}-\\d{6}";

    private final PartLotService partLotService;

    @Operation(
            summary = "LOT 이 쓰인 차",
            description = "부품 LOT 하나가 들어간 완성차를 먼저 완성된 차부터 본다 (리콜 대상 찾기). 상태로 창고에 있는지 출고됐는지 알 수 있다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공. 아직 쓰인 차가 없으면 빈 목록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "lotNo 가 LOT 번호 형식이 아님 (LOT-숫자 6자리-숫자 6자리)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "LOT 없음 (PART_LOT_NOT_FOUND)")
    })
    @GetMapping("/{lotNo}/vehicles")
    public ApiResponse<List<LotVehicleResponse>> findVehicles(
            @Parameter(description = "LOT 번호", example = "LOT-261017-000012")
                    @PathVariable("lotNo")
                    @Pattern(regexp = LOT_NO)
                    String lotNo) {
        List<LotVehicleResponse> vehicles = partLotService.findVehiclesOfLot(lotNo);
        return ApiResponse.ok(vehicles);
    }
}
