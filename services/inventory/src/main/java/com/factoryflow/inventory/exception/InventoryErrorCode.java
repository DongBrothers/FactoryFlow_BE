package com.factoryflow.inventory.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum InventoryErrorCode implements ErrorCode {
    PART_NOT_FOUND(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."),
    PART_CODE_DUPLICATED(HttpStatus.CONFLICT, "같은 코드의 부품이 이미 있습니다."),
    INSUFFICIENT_AVAILABLE(HttpStatus.CONFLICT, "바로 쓸 수 있는 수량보다 많이 줄일 수 없습니다."),
    PART_CONFLICT(HttpStatus.CONFLICT, "다른 작업이 먼저 바꿨습니다. 다시 조회해 주세요."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "생산 건에 잡아 둔 부품이 없습니다."),
    FINISHED_VEHICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "완성차를 찾을 수 없습니다."),
    FINISHED_VEHICLE_ALREADY_SHIPPED(HttpStatus.CONFLICT, "이미 출고된 차입니다."),
    FINISHED_VEHICLE_CONFLICT(HttpStatus.CONFLICT, "다른 작업이 먼저 바꿨습니다. 다시 조회해 주세요."),
    PART_LOT_NOT_FOUND(HttpStatus.NOT_FOUND, "부품 LOT 을 찾을 수 없습니다."),
    PART_LOT_SHORTAGE(HttpStatus.INTERNAL_SERVER_ERROR, "LOT 남은 수량이 잡아 둔 부품보다 적습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
