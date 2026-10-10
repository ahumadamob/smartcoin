package com.smartcoin.movement.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.smartcoin.movement.service.MovementService.MovementRow;
import com.smartcoin.shared.domain.Currency;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Movement", description = "Un cobro o pago real de una partida (RN-21), con su cuenta.")
public record MovementResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1204")
		Long id,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512", description = "Partida a la que pertenece.")
		Long entryId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-11-05")
		LocalDate date,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "70000.00",
				description = "Siempre mayor que 0: suma o resta en la cuenta según el tipo de la partida (RN-35).")
		BigDecimal amount,

		@Schema(nullable = true, example = "Primera cuota de las expensas")
		String note,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
		Long accountId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
		String accountName,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Moneda de la cuenta del movimiento.")
		Currency currency) {

	static MovementResponse from(MovementRow row) {
		return new MovementResponse(row.id(), row.entryId(), row.date(), row.amount(), row.note(), row.accountId(),
				row.accountName(), row.currency());
	}
}
