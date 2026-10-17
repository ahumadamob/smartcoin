package com.smartcoin.report.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.smartcoin.report.domain.CashFlowCalculator.CashFlowRowStatus;
import com.smartcoin.report.domain.CashFlowCalculator.CashFlowRowType;
import com.smartcoin.report.domain.CashFlowCalculator.Row;
import com.smartcoin.report.service.CashFlowService.CashFlow;
import com.smartcoin.shared.domain.Currency;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CashFlow", description = "Flujo de caja de una moneda para un rango de fechas (RN-45, D-46): saldo de "
		+ "arranque, filas en orden cronológico con el saldo acumulado y saldo final. Nunca mezcla monedas.")
public record CashFlowResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		Currency currency,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-11-01",
				description = "Primer día del rango, ya completado si no se pidió.")
		LocalDate from,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-11-30",
				description = "Último día del rango, ya completado si no se pidió.")
		LocalDate to,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "340000.00",
				description = "Suma de los saldos de las cuentas de la moneda al día anterior al rango. Si el rango "
						+ "empieza después de hoy es proyectado: suma además los pendientes con fecha efectiva "
						+ "anterior al rango.")
		BigDecimal openingBalance,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-10-31",
				description = "El día anterior al rango: la fecha a la que corresponde el saldo de arranque.")
		LocalDate openingBalanceDate,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "El saldo de arranque incluye pendientes "
				+ "que todavía no ocurrieron: el rango empieza después de hoy.")
		boolean openingBalanceProjected,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		List<CashFlowRowResponse> rows,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "190000.00",
				description = "El saldo acumulado de la última fila, o el de arranque si no hay filas.")
		BigDecimal closingBalance) {

	public static CashFlowResponse from(CashFlow flow) {
		return new CashFlowResponse(flow.currency(), flow.from(), flow.to(), flow.result().openingBalance(),
				flow.from().minusDays(1), flow.result().openingBalanceProjected(),
				flow.result().rows().stream().map(CashFlowRowResponse::from).toList(),
				flow.result().closingBalance());
	}

	@Schema(name = "CashFlowRow", description = "Una fila del flujo de caja: algo que ya ocurrió (real) o que se "
			+ "espera (prevista).")
	public record CashFlowRowResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-11-25",
					description = "Fecha del movimiento o transferencia, de apertura de la cuenta, o la fecha efectiva "
							+ "de un pendiente: su vencimiento, u hoy si ya venció.")
			LocalDate date,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			CashFlowRowStatus status,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			CashFlowRowType type,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Sueldo · Banco Nación")
			String description,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "-150000.00",
					description = "Con signo: positivo suma a la moneda, negativo resta.")
			BigDecimal amount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "190000.00",
					description = "Saldo acumulado después de esta fila. Puede ser negativo.")
			BigDecimal balance,

			@Schema(nullable = true, type = "string", format = "date", example = "2026-10-10",
					description = "Solo en las filas de partidas pendientes: el vencimiento original.")
			LocalDate dueDate,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Fila de una partida pendiente cuyo "
					+ "vencimiento es anterior a hoy (RN-20); su fecha es hoy.")
			boolean overdue) {

		static CashFlowRowResponse from(Row row) {
			return new CashFlowRowResponse(row.date(), row.status(), row.type(), row.description(), row.amount(),
					row.balance(), row.dueDate(), row.overdue());
		}
	}
}
