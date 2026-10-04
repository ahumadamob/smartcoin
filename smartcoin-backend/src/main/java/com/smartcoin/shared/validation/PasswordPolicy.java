package com.smartcoin.shared.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Política de contraseñas (RN-51): mínimo {@code app.security.password-min-length} caracteres y, por el límite de
 * BCrypt, máximo 72 bytes en UTF-8. Un valor nulo es válido: se exige con {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = PasswordPolicyValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordPolicy {

	String message() default "La contraseña no cumple la política de contraseñas";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
