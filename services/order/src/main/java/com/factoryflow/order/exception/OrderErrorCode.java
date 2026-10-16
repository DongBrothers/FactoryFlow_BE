package com.factoryflow.order.exception;

import com.factoryflow.common.web.exception.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "주문한 제품을 찾을 수 없습니다."),
    BOM_NOT_FOUND(HttpStatus.NOT_FOUND, "부품 구성(BOM)이 없어 주문할 수 없는 제품입니다."),
    PRODUCTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "제품 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 품목을 찾을 수 없습니다."),
    UNKNOWN_PRODUCTION_STATUS(HttpStatus.BAD_REQUEST, "알 수 없는 생산 상태입니다."),
    ORDER_VEHICLE_OVERFLOW(HttpStatus.CONFLICT, "주문 수량보다 많은 생산 건이 들어왔습니다."),
    ORDER_VEHICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 차량을 찾을 수 없습니다."),
    ORDER_NOT_READY(HttpStatus.CONFLICT, "아직 생산 계획 중입니다. 잠시 후 다시 시도해 주세요."),
    ORDER_CANNOT_BE_CANCELLED(HttpStatus.CONFLICT, "생산이 끝났거나 출고된 차량이 있어 취소할 수 없습니다."),
    CANCEL_IN_PROGRESS(HttpStatus.CONFLICT, "이미 취소를 요청해 처리 중입니다."),
    ORDER_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 주문입니다."),
    ORDER_CONFLICT(HttpStatus.CONFLICT, "다른 작업이 먼저 바꿨습니다. 다시 조회해 주세요.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
