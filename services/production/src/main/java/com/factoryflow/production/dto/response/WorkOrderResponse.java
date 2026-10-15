package com.factoryflow.production.dto.response;

import com.factoryflow.production.domain.ProductionProcess;
import com.factoryflow.production.domain.WorkOrder;
import com.factoryflow.production.domain.WorkOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "작업지시 목록 항목")
public record WorkOrderResponse(
        @Schema(description = "작업지시 번호", example = "12") Long workOrderId,
        @Schema(description = "생산 건 번호 (차량 1대)", example = "101") Long productionId,
        @Schema(description = "주문 번호 (order 서비스의 주문 id)", example = "42") Long orderId,
        @Schema(description = "제품 번호", example = "1") Long productId,
        @Schema(description = "작업지시 상태", example = "RELEASED") WorkOrderStatus status,
        @Schema(description = "지금 할 공정 (끝나지 않은 첫 공정. 모두 끝났으면 없음)", example = "PRESS")
                ProductionProcess currentProcess) {

    public static WorkOrderResponse of(WorkOrder workOrder, ProductionProcess currentProcess) {
        return new WorkOrderResponse(
                workOrder.getId(),
                workOrder.getProductionId(),
                workOrder.getOrderId(),
                workOrder.getProductId(),
                workOrder.getStatus(),
                currentProcess);
    }
}
