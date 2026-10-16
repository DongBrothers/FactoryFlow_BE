package com.factoryflow.simulator.controller;

import com.factoryflow.common.web.response.ApiResponse;
import com.factoryflow.simulator.domain.EquipmentStatus;
import com.factoryflow.simulator.dto.response.InternalEquipmentResponse;
import com.factoryflow.simulator.service.EquipmentService;
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

    private final EquipmentService equipmentService;

    @GetMapping
    public ApiResponse<List<InternalEquipmentResponse>> findEquipments() {
        List<InternalEquipmentResponse> equipments = equipmentService.findEquipments();
        return ApiResponse.ok(equipments);
    }

    @PostMapping("/{code}/fault")
    public ApiResponse<InternalEquipmentResponse> fault(@PathVariable("code") String code) {
        InternalEquipmentResponse equipment =
                equipmentService.changeStatus(code, EquipmentStatus.FAULT);
        return ApiResponse.ok(equipment);
    }

    @PostMapping("/{code}/stop")
    public ApiResponse<InternalEquipmentResponse> stop(@PathVariable("code") String code) {
        InternalEquipmentResponse equipment =
                equipmentService.changeStatus(code, EquipmentStatus.STOPPED);
        return ApiResponse.ok(equipment);
    }

    @PostMapping("/{code}/run")
    public ApiResponse<InternalEquipmentResponse> run(@PathVariable("code") String code) {
        InternalEquipmentResponse equipment =
                equipmentService.changeStatus(code, EquipmentStatus.RUNNING);
        return ApiResponse.ok(equipment);
    }
}
