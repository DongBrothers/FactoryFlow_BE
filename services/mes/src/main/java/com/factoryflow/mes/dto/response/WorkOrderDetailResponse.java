package com.factoryflow.mes.dto.response;

import com.factoryflow.mes.domain.WorkOrder;
import com.factoryflow.mes.domain.WorkOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "작업지시 상세: 공정 5개와 공정별 실적")
public record WorkOrderDetailResponse(
        @Schema(description = "작업지시 번호", example = "12") Long workOrderId,
        @Schema(description = "생산 건 번호 (차량 1대)", example = "101") Long productionId,
        @Schema(description = "주문 번호 (order 서비스의 주문 id)", example = "42") Long orderId,
        @Schema(description = "제품 번호", example = "1") Long productId,
        @Schema(description = "작업지시 상태", example = "IN_PROGRESS") WorkOrderStatus status,
        @Schema(description = "차대번호 (검사 완료 후 발행)", example = "KMHXX81CBRU000001") String vin,
        @Schema(description = "공정 5개, 라우팅 순서") List<WorkOrderStepResponse> steps) {

    public static WorkOrderDetailResponse of(
            WorkOrder workOrder, List<WorkOrderStepResponse> steps) {
        return new WorkOrderDetailResponse(
                workOrder.getId(),
                workOrder.getProductionId(),
                workOrder.getOrderId(),
                workOrder.getProductId(),
                workOrder.getStatus(),
                workOrder.getVin(),
                steps);
    }
}
