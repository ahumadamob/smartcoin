package com.smartcoin.entry.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.smartcoin.entry.service.EntryService.Changes;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Cambios de una partida (RN-18). Un campo omitido o `null` significa «no cambia»; enviar el "
		+ "mismo valor que ya tiene tampoco es un cambio. Para vaciar la categoría se usa `clearCategory`, porque "
		+ "`null` no distingue «no enviado» de «vaciar». En una partida recurrente solo se edita "
		+ "`budgetedAmount` (queda marcada como editada); sus demás datos son los de su Concepto.")
public record EntryUpdateRequest(

		@Schema(nullable = true, maxLength = 100, example = "Service del auto",
				description = "Nombre nuevo. Se guarda sin espacios en los extremos.")
		@Size(max = 100, message = "El nombre no puede superar los 100 caracteres.")
		@Pattern(regexp = ".*\\S.*", message = "El nombre no puede estar vacío.")
		String name,

		@Schema(nullable = true,
				description = "El tipo no se edita: solo se acepta el que ya tiene la partida, y uno distinto es "
						+ "409 FIELD_NOT_EDITABLE.")
		EntryKind kind,

		@Schema(nullable = true, example = "12",
				description = "Cuenta nueva, del usuario. Con movimientos, solo otra de la misma moneda.")
		Long accountId,

		@Schema(nullable = true, example = "3", description = "Categoría nueva, del usuario.")
		Long categoryId,

		@Schema(nullable = true, description = "`true` para dejar la partida sin categoría. Omitido, `null` o "
				+ "`false`, no hace nada. No se combina con `categoryId`.")
		Boolean clearCategory,

		@Schema(nullable = true, type = "string", format = "date", example = "2026-11-18",
				description = "Vencimiento nuevo: entre el primer día del mes anterior al período de la partida y el "
						+ "último día del período, ambos inclusive.")
		LocalDate dueDate,

		@Schema(nullable = true, minimum = "0", example = "85000.00", description = "Presupuestado nuevo, con hasta "
				+ "2 decimales.")
		@DecimalMin(value = "0", message = "El presupuestado no puede ser negativo.")
		@Digits(integer = 17, fraction = 2, message = "El presupuestado admite hasta 2 decimales.")
		BigDecimal budgetedAmount) {

	Changes toChanges() {
		return new Changes(name, kind, accountId, categoryId, Boolean.TRUE.equals(clearCategory), dueDate,
				budgetedAmount);
	}
}
