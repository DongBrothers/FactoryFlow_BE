package com.factoryflow.mes.dto.request;

import com.factoryflow.mes.domain.StepResult;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DefectReasonRequiredValidator
        implements ConstraintValidator<DefectReasonRequired, CompleteWorkOrderStepRequest> {

    /** result 가 없는 경우는 @NotNull 이 막는다. */
    @Override
    public boolean isValid(
            CompleteWorkOrderStepRequest request, ConstraintValidatorContext context) {
        if (request == null || request.result() != StepResult.DEFECT) {
            return true;
        }
        return request.defectReason() != null && !request.defectReason().isBlank();
    }
}
