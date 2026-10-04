package com.smartcoin.user.web;

import java.time.YearMonth;

import com.smartcoin.user.domain.User;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Datos del usuario autenticado. Nunca incluye la contraseña.")
public record UserResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Identificador del usuario.", example = "1")
		Long id,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Email, en minúsculas.", example = "persona@ejemplo.com")
		String email,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Si es true, el usuario debe cambiar su contraseña antes de seguir.")
		boolean mustChangePassword,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Período inicial (YYYY-MM).", example = "2026-10")
		YearMonth startPeriod) {

	static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.isMustChangePassword(), user.getStartPeriod());
	}
}
