package com.factoryflow.mes.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 센서값이 있는 로봇 공정(PRESS, BODY, PAINT)이어야 한다. */
@Documented
@Constraint(validatedBy = RobotProcessValidator.class)
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RobotProcess {

    String message() default "센서값은 로봇 공정(PRESS, BODY, PAINT)에만 있습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
