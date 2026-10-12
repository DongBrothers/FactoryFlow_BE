package com.factoryflow.order.dto.request;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public class UniqueProductIdsValidator
        implements ConstraintValidator<UniqueProductIds, List<CreateOrderItemRequest>> {

    /** null 이나 productId 가 빠진 항목은 다른 제약(@NotEmpty, @NotNull)이 막는다. */
    @Override
    public boolean isValid(List<CreateOrderItemRequest> items, ConstraintValidatorContext context) {
        if (items == null) {
            return true;
        }
        List<Long> productIds =
                items.stream()
                        .filter(Objects::nonNull)
                        .map(CreateOrderItemRequest::productId)
                        .filter(Objects::nonNull)
                        .toList();
        return productIds.size() == new HashSet<>(productIds).size();
    }
}
