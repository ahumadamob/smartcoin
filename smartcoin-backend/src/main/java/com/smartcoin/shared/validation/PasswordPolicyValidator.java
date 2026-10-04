package com.smartcoin.shared.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.smartcoin.shared.config.AppProperties;

public class PasswordPolicyValidator implements ConstraintValidator<PasswordPolicy, String> {

	/** BCrypt solo usa los primeros 72 bytes y Spring Security rechaza las contraseñas más largas. */
	static final int MAX_BYTES = 72;

	private final int minLength;

	public PasswordPolicyValidator(AppProperties properties) {
		this.minLength = properties.security().passwordMinLength();
	}

	@Override
	public boolean isValid(String password, ConstraintValidatorContext context) {
		if (password == null) {
			return true;
		}
		String violation = null;
		if (password.length() < minLength) {
			violation = "La contraseña debe tener al menos " + minLength + " caracteres.";
		}
		else if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			violation = "La contraseña no puede superar los " + MAX_BYTES + " bytes en UTF-8.";
		}
		if (violation == null) {
			return true;
		}
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(violation).addConstraintViolation();
		return false;
	}
}
