package com.factoryflow.mes.dto.request;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Duration;

public class TelemetryRangeValidator
        implements ConstraintValidator<TelemetryRange, TelemetryRangeRequest> {

    static final Duration MAX_RANGE = Duration.ofHours(1);

    /** from, to 가 없는 경우는 @NotNull 이 막는다. */
    @Override
    public boolean isValid(TelemetryRangeRequest request, ConstraintValidatorContext context) {
        if (request == null || request.from() == null || request.to() == null) {
            return true;
        }
        return request.from().isBefore(request.to())
                && Duration.between(request.from(), request.to()).compareTo(MAX_RANGE) <= 0;
    }
}
