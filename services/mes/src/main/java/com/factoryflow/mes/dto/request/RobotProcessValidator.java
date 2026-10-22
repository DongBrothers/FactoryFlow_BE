package com.factoryflow.mes.dto.request;

import com.factoryflow.mes.domain.ProductionProcess;
import com.factoryflow.mes.domain.TelemetryParameter;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class RobotProcessValidator implements ConstraintValidator<RobotProcess, ProductionProcess> {

    /** 값이 없으면 경로 변수 바인딩이 먼저 막는다. */
    @Override
    public boolean isValid(ProductionProcess process, ConstraintValidatorContext context) {
        return process == null || !TelemetryParameter.of(process).isEmpty();
    }
}
