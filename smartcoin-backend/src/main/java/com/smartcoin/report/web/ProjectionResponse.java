package com.smartcoin.report.web;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.report.domain.ProjectionCalculator.AccountStart;
import com.smartcoin.report.domain.ProjectionCalculator.Result;
import com.smartcoin.shared.domain.Currency;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Projection", description = "Proyección de saldos por cuenta (RN-46): para cada mes desde el actual "
		+ "hasta `until`, el saldo proyectado de cada cuenta al último día del mes y los subtotales por moneda. "
		+ "Supone que cada pendiente se cobra o paga en la cuenta prevista de su partida y en su fecha efectiva, y no "
		+ "incluye transferencias futuras. Nunca suma monedas.")
public record ProjectionResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10",
				description = "Primer mes: el actual.")
		YearMonth from,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2028-10",
				description = "Último mes, ya completado si no se pidió: el horizonte.")
		YearMonth until,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Las cuentas del usuario, por moneda y nombre: el orden de `balances` en cada mes.")
		List<ProjectionAccountResponse> accounts,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Un elemento por mes, en orden.")
		List<ProjectionMonthResponse> months) {

	public static ProjectionResponse from(Result result) {
		return new ProjectionResponse(result.from(), result.until(),
				result.accounts().stream().map(ProjectionAccountResponse::from).toList(),
				result.months().stream().map(ProjectionMonthResponse::from).toList());
	}

	@Schema(name = "ProjectionAccount", description = "Una cuenta de la proyección.")
	public record ProjectionAccountResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			long id,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
			String name,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			Currency currency) {

		static ProjectionAccountResponse from(AccountStart account) {
			return new ProjectionAccountResponse(account.id(), account.name(), account.currency());
		}
	}

	@Schema(name = "ProjectionMonth", description = "La proyección al último día de un mes.")
	public record ProjectionMonthResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-11")
			YearMonth month,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Un saldo por cuenta, en el mismo orden que `accounts`.")
			List<ProjectionBalanceResponse> balances,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Un subtotal por cada moneda que tiene cuentas, en el orden ARS, USD.")
			List<ProjectionSubtotalResponse> subtotals) {

		static ProjectionMonthResponse from(com.smartcoin.report.domain.ProjectionCalculator.Month month) {
			return new ProjectionMonthResponse(month.month(),
					month.balances().stream()
							.map(b -> new ProjectionBalanceResponse(b.accountId(), b.balance())).toList(),
					month.subtotals().stream()
							.map(s -> new ProjectionSubtotalResponse(s.currency(), s.balance())).toList());
		}
	}

	@Schema(name = "ProjectionBalance", description = "Saldo proyectado de una cuenta.")
	public record ProjectionBalanceResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			long accountId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "340000.00",
					description = "Saldo actual más los pendientes con fecha efectiva hasta el último día del mes.")
			BigDecimal balance) {
	}

	@Schema(name = "ProjectionSubtotal", description = "Subtotal de una moneda.")
	public record ProjectionSubtotalResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "360000.00")
			BigDecimal balance) {
	}
}
