package com.smartcoin.period.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.period.domain.MonthTotals;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.period.service.PeriodViewService.View;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PeriodView", description = "Vista del mes (HU-15, RN-44): el período, sus partidas separadas en "
		+ "ingresos y gastos, y los totales por moneda. Todos los montos los calcula el backend.")
public record PeriodViewResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-11")
		YearMonth period,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "OPEN o CLOSED. Un período cerrado se ve igual, pero no admite cambios (RN-09).")
		PeriodStatus status,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-08",
				description = "Período inicial del usuario: no hay períodos anteriores.")
		YearMonth startPeriod,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10",
				description = "Período actual: el mes de hoy en la zona de la aplicación.")
		YearMonth currentPeriod,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2028-10",
				description = "Último período que existe para el usuario: no hay períodos posteriores.")
		YearMonth horizon,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Partidas de ingreso, por vencimiento, nombre y orden de creación.")
		List<PeriodEntry> incomes,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Partidas de gasto, por vencimiento, nombre y orden de creación.")
		List<PeriodEntry> expenses,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Un total por cada moneda que tiene partidas en el período, en el orden ARS, USD. "
						+ "Vacía si el período no tiene partidas. Nunca hay un total que mezcle monedas (RN-04).")
		List<CurrencyTotals> totals) {

	@Schema(name = "PeriodEntry", description = "Una partida en la vista del mes, con sus valores derivados "
			+ "(RN-16, RN-17, RN-20).")
	public record PeriodEntry(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512")
			Long id,

			@Schema(nullable = true, example = "31", description = "Concepto del que viene; nulo si no es recurrente.")
			Long budgetItemId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			EntryOrigin origin,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			EntryKind kind,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Luz",
					description = "En una partida recurrente, el nombre de su Concepto.")
			String name,

			@Schema(nullable = true, example = "3")
			Long categoryId,

			@Schema(nullable = true, example = "Servicios",
					description = "En una partida recurrente, la categoría de su Concepto.")
			String categoryName,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
			Long accountId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
			String accountName,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Moneda de la partida: la de su cuenta (RN-04).")
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-11-18",
					description = "Vencimiento. Puede caer en el mes anterior al período (desfase −1).")
			LocalDate dueDate,

			@Schema(nullable = true, example = "5", description = "Número de cuota; nulo si no es en cuotas.")
			Integer installmentNumber,

			@Schema(nullable = true, example = "12",
					description = "Total de cuotas de su Concepto; nulo si no es en cuotas.")
			Integer installmentsTotal,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "45000.00")
			BigDecimal budgetedAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "20000.00",
					description = "Real: suma de sus movimientos.")
			BigDecimal actualAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "25000.00",
					description = "Pendiente: presupuestado menos real, nunca negativo; 0 si está consolidada.")
			BigDecimal pendingAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "45000.00",
					description = "Estimado: real más pendiente; en una consolidada, el monto consolidado.")
			BigDecimal forecastAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "ESTIMATED: pendiente sin movimientos. PARTIAL: pendiente con movimientos. "
							+ "CONSOLIDATED: consolidada.")
			EntryStatus status,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Partida editada: el usuario cambió a mano su presupuestado.")
			boolean manual,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Vencida: pendiente y con vencimiento anterior a hoy.")
			boolean overdue) {

		public static PeriodEntry from(EntryRow row) {
			return new PeriodEntry(row.id(), row.budgetItemId(), row.origin(), row.kind(), row.name(),
					row.categoryId(), row.categoryName(), row.accountId(), row.accountName(), row.currency(),
					row.dueDate(), row.installmentNumber(), row.installmentsTotal(), row.budgetedAmount(),
					row.amounts().actual(), row.amounts().pending(), row.amounts().forecast(),
					row.amounts().status(), row.manual(), row.overdue());
		}
	}

	@Schema(name = "PeriodCurrencyTotals", description = "Totales de una moneda en el período (RN-44).")
	public record CurrencyTotals(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Totales de las partidas de ingreso.")
			SideTotals income,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Totales de las partidas de gasto.")
			SideTotals expense,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1115000.00",
					description = "Resultado: estimado de ingresos menos estimado de gastos. Puede ser negativo.")
			BigDecimal result) {

		static CurrencyTotals from(MonthTotals.CurrencyTotals totals) {
			return new CurrencyTotals(totals.currency(), SideTotals.from(totals.income()),
					SideTotals.from(totals.expense()), totals.result());
		}
	}

	@Schema(name = "PeriodSideTotals", description = "Totales de los ingresos o de los gastos de una moneda.")
	public record SideTotals(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "3",
					description = "Cuántas partidas suma. Con 0, la moneda no tiene partidas de ese tipo y todos "
							+ "los montos son 0.")
			int entryCount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "735000.00")
			BigDecimal budgetedAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "470000.00")
			BigDecimal actualAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "265000.00")
			BigDecimal pendingAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "735000.00")
			BigDecimal forecastAmount) {

		static SideTotals from(MonthTotals.SideTotals totals) {
			return new SideTotals(totals.entryCount(), totals.budgeted(), totals.actual(), totals.pending(),
					totals.forecast());
		}
	}

	static PeriodViewResponse from(View view) {
		return new PeriodViewResponse(view.period(), view.status(), view.startPeriod(), view.currentPeriod(),
				view.horizon(), view.incomes().stream().map(PeriodEntry::from).toList(),
				view.expenses().stream().map(PeriodEntry::from).toList(),
				view.totals().stream().map(CurrencyTotals::from).toList());
	}
}
