package com.factoryflow.mes.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 실적이 불량(DEFECT)이면 불량 사유가 있어야 한다. */
@Documented
@Constraint(validatedBy = DefectReasonRequiredValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DefectReasonRequired {

    String message() default "불량이면 불량 사유를 넣어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
