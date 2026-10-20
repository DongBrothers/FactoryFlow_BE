package com.factoryflow.inventory.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.inventory.dto.response.StorageSlotListResponse;
import com.factoryflow.inventory.service.StorageSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "완성차 자리", description = "완성차 출고장 자리 현황")
@RestController
@RequestMapping("/inventory/storage-slots")
@RequiredArgsConstructor
public class StorageSlotController {

    private final StorageSlotService storageSlotService;

    @Operation(
            summary = "완성차 자리 현황",
            description =
                    "출고장 자리 40개를 코드 순으로 본다. 자리마다 서 있는 완성차의 차대번호(비었으면 없음), 차가 선 자리 수, 자리가 없어 기다리는 완성차 수를 준다. 자리는 완성차가 들어올 때 자동으로 정해진다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<StorageSlotListResponse> findSlots() {
        StorageSlotListResponse slots = storageSlotService.findSlots();
        return ApiResponse.ok(slots);
    }
}
