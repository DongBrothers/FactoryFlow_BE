package com.factoryflow.simulator.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.simulator.domain.EquipmentStatus;
import com.factoryflow.simulator.dto.response.InternalEquipmentResponse;
import com.factoryflow.simulator.service.EquipmentService;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 가상 설비 상태 보기·바꾸기 (시연·테스트용 내부 API, gateway 에 노출하지 않음). */
@RestController
@RequestMapping("/internal/simulator/equipments")
@RequiredArgsConstructor
public class InternalEquipmentController {

    /** 설비 코드 형식: {공정}-{두 자리 번호} (예: PRESS-01). 아니면 찾기 전에 400. */
    private static final String EQUIPMENT_CODE = "[A-Z]+-\\d{2}";

    private final EquipmentService equipmentService;

    @GetMapping
    public ApiResponse<List<InternalEquipmentResponse>> findEquipments() {
        List<InternalEquipmentResponse> equipments = equipmentService.findEquipments();
        return ApiResponse.ok(equipments);
    }

    @PostMapping("/{code}/fault")
    public ApiResponse<InternalEquipmentResponse> fault(
            @PathVariable("code") @Pattern(regexp = EQUIPMENT_CODE) String code) {
        InternalEquipmentResponse equipment =
                equipmentService.changeStatus(code, EquipmentStatus.FAULT);
        return ApiResponse.ok(equipment);
    }

    @PostMapping("/{code}/stop")
    public ApiResponse<InternalEquipmentResponse> stop(
            @PathVariable("code") @Pattern(regexp = EQUIPMENT_CODE) String code) {
        InternalEquipmentResponse equipment =
                equipmentService.changeStatus(code, EquipmentStatus.STOPPED);
        return ApiResponse.ok(equipment);
    }

    @PostMapping("/{code}/run")
    public ApiResponse<InternalEquipmentResponse> run(
            @PathVariable("code") @Pattern(regexp = EQUIPMENT_CODE) String code) {
        InternalEquipmentResponse equipment =
                equipmentService.changeStatus(code, EquipmentStatus.RUNNING);
        return ApiResponse.ok(equipment);
    }
}
