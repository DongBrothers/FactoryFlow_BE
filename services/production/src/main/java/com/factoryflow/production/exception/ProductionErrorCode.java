package com.factoryflow.production.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductionErrorCode implements ErrorCode {
    PRODUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "생산 건을 찾을 수 없습니다."),
    PRODUCTION_NOT_READY(HttpStatus.CONFLICT, "부품이 다 잡혀 작업지시를 기다리는 생산 건이 아닙니다."),
    WORK_ORDER_ALREADY_RELEASED(HttpStatus.CONFLICT, "이미 작업지시를 발행했습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "제품을 찾을 수 없습니다."),
    PRODUCT_CODE_DUPLICATED(HttpStatus.CONFLICT, "같은 코드의 제품이 이미 있습니다."),
    PART_NOT_FOUND(HttpStatus.NOT_FOUND, "BOM 의 부품을 찾을 수 없습니다."),
    INVENTORY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "부품 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_ORDER_QTY(HttpStatus.BAD_REQUEST, "주문 수량은 1 이상이어야 합니다."),
    WORK_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "작업지시를 찾을 수 없습니다."),
    STEP_NOT_FOUND(HttpStatus.NOT_FOUND, "공정을 찾을 수 없습니다."),
    EQUIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "설비를 찾을 수 없습니다."),
    WORK_ORDER_CLOSED(HttpStatus.CONFLICT, "취소된 작업지시입니다."),
    STEP_OUT_OF_ORDER(HttpStatus.CONFLICT, "앞 공정이 끝나지 않았습니다."),
    INVALID_STEP_STATUS(HttpStatus.CONFLICT, "지금 공정 상태에서는 할 수 없는 작업입니다."),
    EQUIPMENT_PROCESS_MISMATCH(HttpStatus.CONFLICT, "이 공정의 설비가 아닙니다."),
    EQUIPMENT_NOT_RUNNING(HttpStatus.CONFLICT, "설비가 가동 중이 아닙니다."),
    EQUIPMENT_BUSY(HttpStatus.CONFLICT, "설비가 다른 차를 작업 중입니다."),
    PRODUCTION_CONFLICT(HttpStatus.CONFLICT, "다른 작업이 먼저 바꿨습니다. 다시 조회해 주세요."),
    VIN_RANGE_EXCEEDED(HttpStatus.INTERNAL_SERVER_ERROR, "차대번호 자리를 넘어 발행할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
