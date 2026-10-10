package com.smartcoin.movement.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.smartcoin.movement.service.MovementService.NewMovement;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Un cobro o pago real de una partida (RN-21). El usuario sale del token; la moneda y el "
		+ "signo en el saldo salen de la partida.")
public record MovementRequest(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-11-05",
				description = "Fecha del cobro o pago. No anterior al primer día del período de la partida menos la "
						+ "ventana de anticipación, ni a la apertura de la cuenta; no posterior a hoy; y en un mes "
						+ "que sea un período abierto.")
		@NotNull(message = "La fecha es obligatoria.")
		LocalDate date,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, exclusiveMinimum = true, minimum = "0",
				example = "70000.00", description = "Monto mayor que 0, con hasta 2 decimales.")
		@NotNull(message = "El monto es obligatorio.")
		@DecimalMin(value = "0", inclusive = false, message = "El monto tiene que ser mayor que 0.")
		@Digits(integer = 17, fraction = 2, message = "El monto admite hasta 2 decimales.")
		BigDecimal amount,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12",
				description = "Cuenta de donde sale o a donde entra la plata, del usuario. Puede no ser la prevista "
						+ "de la partida, pero tiene que ser de su misma moneda (S-02).")
		@NotNull(message = "La cuenta es obligatoria.")
		Long accountId,

		@Schema(nullable = true, maxLength = 200, example = "Primera cuota de las expensas",
				description = "Nota opcional. Se guarda sin espacios en los extremos; en blanco es sin nota.")
		@Size(max = 200, message = "La nota no puede superar los 200 caracteres.")
		String note) {

	NewMovement toValues() {
		return new NewMovement(date, amount, accountId, note);
	}
}
