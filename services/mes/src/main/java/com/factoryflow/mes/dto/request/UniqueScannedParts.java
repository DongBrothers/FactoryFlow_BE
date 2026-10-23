package com.factoryflow.mes.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 찍은 LOT 목록에 같은 부품이 두 번 없어야 한다 (부품마다 LOT 하나). */
@Documented
@Constraint(validatedBy = UniqueScannedPartsValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueScannedParts {

    String message() default "같은 부품을 두 번 찍을 수 없습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
