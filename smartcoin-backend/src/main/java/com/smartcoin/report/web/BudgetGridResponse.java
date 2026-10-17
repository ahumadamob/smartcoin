package com.smartcoin.report.web;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.period.domain.MonthTotals.CurrencyTotals;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.report.service.BudgetGridService.Cell;
import com.smartcoin.report.service.BudgetGridService.Grid;
import com.smartcoin.report.service.BudgetGridService.Month;
import com.smartcoin.report.service.BudgetGridService.MonthTotal;
import com.smartcoin.report.service.BudgetGridService.Row;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BudgetGrid", description = "Vista de varios meses (HU-36): los Conceptos en filas y los meses en "
		+ "columnas, con el estimado de cada partida (RN-17) y los totales por mes y moneda (RN-44). Las partidas sin "
		+ "Concepto van agrupadas por origen, tipo y moneda. Nunca suma monedas.")
public record BudgetGridResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10",
				description = "Primer mes de la grilla.")
		YearMonth from,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2027-09",
				description = "Último mes de la grilla, ya completado si no se pidió.")
		YearMonth to,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10",
				description = "Período actual.")
		YearMonth currentPeriod,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-08",
				description = "Período inicial del usuario: el primer mes que se puede pedir.")
		YearMonth startPeriod,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2028-10",
				description = "Horizonte: el último mes que se puede pedir.")
		YearMonth horizon,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Un elemento por mes, en orden: las columnas.")
		List<GridMonthResponse> months,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Las filas con al menos una partida en el rango: ingresos y después gastos; en cada "
						+ "sección, los Conceptos por nombre y debajo las partidas sin Concepto.")
		List<GridRowResponse> rows,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Un elemento por mes, en el mismo orden.")
		List<GridMonthTotalsResponse> totals) {

	public static BudgetGridResponse from(Grid grid) {
		return new BudgetGridResponse(grid.from(), grid.to(), grid.currentPeriod(), grid.startPeriod(), grid.horizon(),
				grid.months().stream().map(GridMonthResponse::from).toList(),
				grid.rows().stream().map(GridRowResponse::from).toList(),
				grid.totals().stream().map(GridMonthTotalsResponse::from).toList());
	}

	@Schema(name = "GridMonth", description = "Un mes de la grilla.")
	public record GridMonthResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-11")
			YearMonth period,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "`CLOSED` deja marcar el mes cerrado.")
			PeriodStatus status) {

		static GridMonthResponse from(Month month) {
			return new GridMonthResponse(month.period(), month.status());
		}
	}

	@Schema(name = "GridRow", description = "Una fila: un Concepto o un grupo de partidas sin Concepto.")
	public record GridRowResponse(

			@Schema(nullable = true, description = "Id del Concepto; `null` en una fila agrupada.")
			Long budgetItemId,

			@Schema(nullable = true, description = "Origen de las partidas de una fila agrupada (`ONE_OFF`, "
					+ "`CARRIED_OVER` o `CLOSING_DIFFERENCE`); `null` en la fila de un Concepto.")
			EntryOrigin origin,

			@Schema(nullable = true, example = "Sueldo", description = "Nombre del Concepto; `null` en una fila agrupada.")
			String name,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			EntryKind kind,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Moneda de la cuenta por defecto del Concepto, o de las partidas agrupadas.")
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Una celda por mes, en el orden de `months`; `null` donde la fila no tiene partida.")
			List<GridCellResponse> cells) {

		static GridRowResponse from(Row row) {
			return new GridRowResponse(row.budgetItemId(), row.origin(), row.name(), row.kind(), row.currency(),
					row.cells().stream().map(c -> c == null ? null : GridCellResponse.from(c)).toList());
		}
	}

	@Schema(name = "GridCell", description = "Lo que tiene una fila en un mes. Con una partida trae su id y su estado; "
			+ "con varias (solo en filas agrupadas) trae la cantidad y el estimado sumado, sin id ni estado.")
	public record GridCellResponse(

			@Schema(nullable = true, description = "Id de la partida; `null` si la celda suma varias.")
			Long entryId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
			int entryCount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Moneda de la cuenta de la partida.")
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "180000.00",
					description = "Estimado (RN-17), calculado igual que en la vista del mes.")
			BigDecimal forecast,

			@Schema(nullable = true, description = "Estado mostrado (RN-16); `null` si la celda suma varias.")
			EntryStatus status,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Partida editada a mano; en una celda que suma varias, si alguna lo está.")
			boolean manual,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Partida consolidada; en una celda que suma varias, si todas lo están.")
			boolean consolidated) {

		static GridCellResponse from(Cell cell) {
			return new GridCellResponse(cell.entryId(), cell.entryCount(), cell.currency(), cell.forecast(),
					cell.status(), cell.manual(), cell.consolidated());
		}
	}

	@Schema(name = "GridMonthTotals", description = "Totales de un mes, por moneda (RN-44).")
	public record GridMonthTotalsResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-11")
			YearMonth period,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Un total por cada moneda con partidas en el mes, en el orden ARS, USD.")
			List<GridCurrencyTotalsResponse> currencies) {

		static GridMonthTotalsResponse from(MonthTotal total) {
			return new GridMonthTotalsResponse(total.period(),
					total.totals().stream().map(GridCurrencyTotalsResponse::from).toList());
		}
	}

	@Schema(name = "GridCurrencyTotals", description = "Estimado de ingresos y de gastos de una moneda en un mes, y el "
			+ "resultado.")
	public record GridCurrencyTotalsResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1200000.00")
			BigDecimal incomeForecast,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "950000.00")
			BigDecimal expenseForecast,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "250000.00",
					description = "Estimado de ingresos menos estimado de gastos; puede ser negativo.")
			BigDecimal result) {

		static GridCurrencyTotalsResponse from(CurrencyTotals totals) {
			return new GridCurrencyTotalsResponse(totals.currency(), totals.income().forecast(),
					totals.expense().forecast(), totals.result());
		}
	}
}
