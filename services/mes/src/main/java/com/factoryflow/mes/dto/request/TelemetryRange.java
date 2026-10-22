package com.factoryflow.mes.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 센서값 조회 범위: from 이 to 보다 앞이고, 길이는 1시간 이하. */
@Documented
@Constraint(validatedBy = TelemetryRangeValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TelemetryRange {

    String message() default "시작 시각은 끝 시각보다 앞이고, 범위는 1시간 이하여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
