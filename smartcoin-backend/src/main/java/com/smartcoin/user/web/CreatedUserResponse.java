package com.smartcoin.user.web;

import java.time.YearMonth;

import com.smartcoin.user.domain.User;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Usuario creado. Nunca incluye la contraseña.")
public record CreatedUserResponse(

		@Schema(description = "Identificador del usuario.", example = "1")
		Long id,

		@Schema(description = "Email, en minúsculas.", example = "persona@ejemplo.com")
		String email,

		@Schema(description = "Período inicial (YYYY-MM).", example = "2026-10")
		YearMonth startPeriod) {

	static CreatedUserResponse from(User user) {
		return new CreatedUserResponse(user.getId(), user.getEmail(), user.getStartPeriod());
	}
}
