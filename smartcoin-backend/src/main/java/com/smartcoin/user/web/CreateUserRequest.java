package com.smartcoin.user.web;

import java.time.YearMonth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.smartcoin.shared.validation.PasswordPolicy;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Datos del usuario a crear.")
public record CreateUserRequest(

		@Schema(description = "Email del usuario. Se guarda en minúsculas y no puede repetirse.",
				example = "persona@ejemplo.com")
		@NotBlank(message = "El email es obligatorio.")
		@Email(message = "El email no tiene un formato válido.")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres.")
		String email,

		@Schema(description = "Contraseña inicial: al menos 10 caracteres y como máximo 72 bytes en UTF-8. "
				+ "El usuario deberá cambiarla en su primer ingreso.",
				accessMode = Schema.AccessMode.WRITE_ONLY)
		@NotBlank(message = "La contraseña es obligatoria.")
		@PasswordPolicy
		String password,

		@Schema(description = "Primer período del usuario (YYYY-MM). Si se omite, el período actual. "
				+ "No puede ser posterior al actual.", example = "2026-10", nullable = true)
		YearMonth startPeriod) {

	/** Sin la contraseña: el {@code toString} de un record la imprimiría y podría terminar en un log. */
	@Override
	public String toString() {
		return "CreateUserRequest[email=" + email + ", startPeriod=" + startPeriod + "]";
	}
}
