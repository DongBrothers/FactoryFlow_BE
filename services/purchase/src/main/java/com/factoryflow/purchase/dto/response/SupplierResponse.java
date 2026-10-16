package com.factoryflow.purchase.dto.response;

import com.factoryflow.purchase.domain.Supplier;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공급업체")
public record SupplierResponse(
        @Schema(description = "공급업체 번호", example = "1") Long supplierId,
        @Schema(description = "이름", example = "현대모비스") String name,
        @Schema(description = "연락처", example = "02-0000-0001") String contact) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getName(), supplier.getContact());
    }
}
