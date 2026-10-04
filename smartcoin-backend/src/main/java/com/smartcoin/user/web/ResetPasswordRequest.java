package com.smartcoin.user.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.smartcoin.shared.validation.PasswordPolicy;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Datos para restablecer la contraseña de un usuario.")
public record ResetPasswordRequest(

		@Schema(description = "Email del usuario, sin distinguir mayúsculas.", example = "persona@ejemplo.com")
		@NotBlank(message = "El email es obligatorio.")
		@Email(message = "El email no tiene un formato válido.")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres.")
		String email,

		@Schema(description = "Contraseña temporal: al menos 10 caracteres y como máximo 72 bytes en UTF-8. "
				+ "El usuario deberá cambiarla en su próximo ingreso.",
				accessMode = Schema.AccessMode.WRITE_ONLY)
		@NotBlank(message = "La contraseña temporal es obligatoria.")
		@PasswordPolicy
		String temporaryPassword) {

	/** Sin la contraseña: el {@code toString} de un record la imprimiría y podría terminar en un log. */
	@Override
	public String toString() {
		return "ResetPasswordRequest[email=" + email + "]";
	}
}
