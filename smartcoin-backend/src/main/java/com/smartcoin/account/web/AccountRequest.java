package com.smartcoin.account.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.smartcoin.account.domain.AccountType;
import com.smartcoin.shared.domain.Currency;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Datos de una cuenta, para el alta y para la edición (reemplaza todos los campos). En la "
		+ "edición, los campos que no se pueden cambiar se envían con su valor actual.")
public record AccountRequest(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100, example = "Banco Nación",
				description = "Nombre, único por usuario sin distinguir mayúsculas. Se guarda sin espacios en los extremos.")
		@NotBlank(message = "El nombre es obligatorio.")
		@Size(max = 100, message = "El nombre no puede superar los 100 caracteres.")
		String name,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Tipo de cuenta.")
		@NotNull(message = "El tipo es obligatorio.")
		AccountType type,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Moneda de la cuenta.")
		@NotNull(message = "La moneda es obligatoria.")
		Currency currency,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-01",
				description = "Desde cuándo se lleva la cuenta: entre el primer día del período inicial del usuario y "
						+ "hoy, ambos inclusive.")
		@NotNull(message = "La fecha de apertura es obligatoria.")
		LocalDate openingDate,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "150000.00",
				description = "Saldo al comienzo de la fecha de apertura, con hasta 2 decimales. Puede ser negativo.")
		@NotNull(message = "El saldo inicial es obligatorio.")
		@Digits(integer = 17, fraction = 2, message = "El saldo inicial admite hasta 2 decimales.")
		BigDecimal initialBalance) {
}
