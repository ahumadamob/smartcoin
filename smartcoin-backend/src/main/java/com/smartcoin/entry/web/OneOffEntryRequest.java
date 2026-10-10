package com.smartcoin.entry.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.smartcoin.entry.service.EntryService.NewOneOff;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Datos de una partida puntual (RN-19): algo que pasa una sola vez y figura solo en el período "
		+ "de la ruta.")
public record OneOffEntryRequest(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100, example = "Service del auto",
				description = "Nombre. Se guarda sin espacios en los extremos. Puede repetirse.")
		@NotBlank(message = "El nombre es obligatorio.")
		@Size(max = 100, message = "El nombre no puede superar los 100 caracteres.")
		String name,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Tipo: ingreso o gasto. No se edita después.")
		@NotNull(message = "El tipo es obligatorio.")
		EntryKind kind,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12",
				description = "Cuenta prevista, del usuario. Define la moneda de la partida.")
		@NotNull(message = "La cuenta es obligatoria.")
		Long accountId,

		@Schema(nullable = true, example = "3", description = "Categoría del usuario. Opcional.")
		Long categoryId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-11-18",
				description = "Vencimiento: entre el primer día del mes anterior al período y el último día del "
						+ "período, ambos inclusive. Puede ser anterior a hoy: la partida nace vencida.")
		@NotNull(message = "El vencimiento es obligatorio.")
		LocalDate dueDate,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", example = "85000.00",
				description = "Presupuestado, con hasta 2 decimales.")
		@NotNull(message = "El presupuestado es obligatorio.")
		@DecimalMin(value = "0", message = "El presupuestado no puede ser negativo.")
		@Digits(integer = 17, fraction = 2, message = "El presupuestado admite hasta 2 decimales.")
		BigDecimal budgetedAmount) {

	NewOneOff toValues() {
		return new NewOneOff(name, kind, accountId, categoryId, dueDate, budgetedAmount);
	}
}
