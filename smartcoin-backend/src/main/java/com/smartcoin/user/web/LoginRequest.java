package com.smartcoin.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Credenciales para iniciar sesión.")
public record LoginRequest(

		@Schema(description = "Email del usuario, sin distinguir mayúsculas.", example = "persona@ejemplo.com")
		@NotBlank(message = "El email es obligatorio.")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres.")
		String email,

		@Schema(description = "Contraseña del usuario.", accessMode = Schema.AccessMode.WRITE_ONLY)
		@NotBlank(message = "La contraseña es obligatoria.")
		@Size(max = 1000, message = "La contraseña es demasiado larga.")
		String password) {

	/** Sin la contraseña: el {@code toString} de un record la imprimiría y podría terminar en un log. */
	@Override
	public String toString() {
		return "LoginRequest[email=" + email + "]";
	}
}
