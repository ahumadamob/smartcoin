package com.smartcoin.transfer.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.smartcoin.shared.domain.Currency;
import com.smartcoin.transfer.service.TransferService.TransferRow;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Transfer", description = "Una transferencia entre dos cuentas propias (RN-36), con sus dos cuentas.")
public record TransferResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "31")
		Long id,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-10-15")
		LocalDate date,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
		Long sourceAccountId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
		String sourceAccountName,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		Currency sourceCurrency,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "50000.00",
				description = "Lo que resta en la cuenta de origen, en su moneda (RN-35).")
		BigDecimal sourceAmount,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "15")
		Long targetAccountId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Efectivo")
		String targetAccountName,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		Currency targetCurrency,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "50000.00",
				description = "Lo que suma en la cuenta de destino, en su moneda (RN-35).")
		BigDecimal targetAmount,

		@Schema(nullable = true, example = "Extracción para gastos de la semana")
		String note,

		@Schema(nullable = true, example = "1250.00", description = "Monto en ARS dividido por monto en USD, con 2 "
				+ "decimales (RN-37). Nulo si las dos cuentas son de la misma moneda. No se guarda.")
		BigDecimal exchangeRate) {

	public static TransferResponse from(TransferRow row) {
		return new TransferResponse(row.id(), row.date(), row.sourceAccountId(), row.sourceAccountName(),
				row.sourceCurrency(), row.sourceAmount(), row.targetAccountId(), row.targetAccountName(),
				row.targetCurrency(), row.targetAmount(), row.note(), row.exchangeRate());
	}
}
