package com.factoryflow.mes.dto.request;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class UniqueScannedPartsValidator
        implements ConstraintValidator<UniqueScannedParts, List<ScannedLotRequest>> {

    /** 목록이 없거나 partId 가 빠진 줄은 다른 제약이 막는다. */
    @Override
    public boolean isValid(List<ScannedLotRequest> scans, ConstraintValidatorContext context) {
        if (scans == null) {
            return true;
        }
        Set<Long> partIds = new HashSet<>();
        return scans.stream()
                .filter(Objects::nonNull)
                .map(ScannedLotRequest::partId)
                .filter(Objects::nonNull)
                .allMatch(partIds::add);
    }
}
