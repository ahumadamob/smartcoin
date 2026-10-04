package com.smartcoin.user.web;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Sesión iniciada: el token y cuándo vence.")
public record LoginResponse(

		@Schema(description = "JWT para enviar en el header Authorization como «Bearer <token>».")
		String token,

		@Schema(description = "Instante (UTC) en que el token vence: 8 horas después de emitido.",
				example = "2026-10-05T20:00:00Z")
		Instant expiresAt,

		@Schema(description = "Si es true, el usuario debe cambiar su contraseña antes de seguir.")
		boolean mustChangePassword) {

	/** Sin el token: el {@code toString} de un record lo imprimiría y podría terminar en un log. */
	@Override
	public String toString() {
		return "LoginResponse[expiresAt=" + expiresAt + ", mustChangePassword=" + mustChangePassword + "]";
	}
}
