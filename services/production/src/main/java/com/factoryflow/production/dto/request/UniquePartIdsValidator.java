package com.factoryflow.production.dto.request;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public class UniquePartIdsValidator
        implements ConstraintValidator<UniquePartIds, List<BomItemRequest>> {

    /** null 이나 partId 가 빠진 줄은 다른 제약(@NotEmpty, @NotNull)이 막는다. */
    @Override
    public boolean isValid(List<BomItemRequest> items, ConstraintValidatorContext context) {
        if (items == null) {
            return true;
        }
        List<Long> partIds =
                items.stream()
                        .filter(Objects::nonNull)
                        .map(BomItemRequest::partId)
                        .filter(Objects::nonNull)
                        .toList();
        return partIds.size() == new HashSet<>(partIds).size();
    }
}
