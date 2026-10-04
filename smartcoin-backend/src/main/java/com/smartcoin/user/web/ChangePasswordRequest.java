package com.smartcoin.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.smartcoin.shared.validation.PasswordPolicy;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Datos para cambiar la contraseña del usuario actual.")
public record ChangePasswordRequest(

		@Schema(description = "Contraseña actual del usuario.", accessMode = Schema.AccessMode.WRITE_ONLY)
		@NotBlank(message = "La contraseña actual es obligatoria.")
		@Size(max = 1000, message = "La contraseña actual es demasiado larga.")
		String currentPassword,

		@Schema(description = "Contraseña nueva: al menos 10 caracteres, como máximo 72 bytes en UTF-8 y distinta de "
				+ "la actual.", accessMode = Schema.AccessMode.WRITE_ONLY)
		@NotBlank(message = "La contraseña nueva es obligatoria.")
		@PasswordPolicy
		String newPassword) {

	/** Sin las contraseñas: el {@code toString} de un record las imprimiría y podrían terminar en un log. */
	@Override
	public String toString() {
		return "ChangePasswordRequest[]";
	}
}
