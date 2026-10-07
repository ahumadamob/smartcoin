package com.smartcoin.budgetitem.web;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.smartcoin.account.web.AccountResponse.FieldState;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.BudgetItemEditability;
import com.smartcoin.budgetitem.domain.BudgetItemEditability.LockedField;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.service.BudgetItemService.Detail;
import com.smartcoin.budgetitem.service.BudgetItemService.EntryCounts;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BudgetItemDetail", description = "Un Concepto con qué datos se pueden editar y cuántas de sus "
		+ "partidas alcanzaría un cambio de monto vigente (RN-15). En un Concepto en cuotas, `endPeriod` es el "
		+ "período de la última cuota, calculado (RN-14).")
public record BudgetItemDetailResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "31")
		Long id,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Monotributo")
		String name,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		EntryKind kind,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
		Long defaultAccountId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Moneda del Concepto: la de su cuenta por defecto (RN-04). La cuenta por defecto solo "
						+ "puede cambiarse por otra de esta moneda.")
		Currency currency,

		@Schema(nullable = true, example = "3")
		Long categoryId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		Periodicity periodicity,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "20")
		Integer dueDay,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
		Integer dueMonthOffset,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10")
		YearMonth startPeriod,

		@Schema(nullable = true, type = "string", example = "2027-12",
				description = "Último período posible. En un Concepto en cuotas, el de la última cuota.")
		YearMonth endPeriod,

		@Schema(nullable = true, example = "12", description = "Total de cuotas, o nulo si no es en cuotas (RN-14).")
		Integer installmentsTotal,

		@Schema(nullable = true, example = "4",
				description = "Cuota que corresponde al período de inicio, o nulo si no es en cuotas.")
		Integer firstInstallmentNumber,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		EstimationRule estimationRule,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "85000.00")
		BigDecimal currentAmount,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Los datos que nunca se editan (RN-15), con el motivo. El resto se edita siempre.")
		Editability editability,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Partidas del Concepto en períodos abiertos que un cambio de monto vigente reemplaza o "
						+ "respeta. Las consolidadas y las de períodos cerrados no cuentan: nunca se tocan.")
		EntryCountsResponse entryCounts) {

	@Schema(name = "BudgetItemEditability", description = "Qué datos de un Concepto no se pueden editar (RN-15, S-12).")
	public record Editability(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) FieldState kind,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) FieldState periodicity,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) FieldState startPeriod,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) FieldState endPeriod,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) FieldState installments) {

		static Editability locked() {
			return new Editability(state(LockedField.KIND), state(LockedField.PERIODICITY),
					state(LockedField.START_PERIOD), state(LockedField.END_PERIOD), state(LockedField.INSTALLMENTS));
		}

		private static FieldState state(LockedField field) {
			return FieldState.from(BudgetItemEditability.of(field));
		}
	}

	@Schema(name = "BudgetItemEntryCounts",
			description = "Cuántas partidas pendientes de períodos abiertos tiene el Concepto, calculado en el backend "
					+ "(RN-15). Una partida parcial, con movimientos, cuenta como pendiente.")
	public record EntryCountsResponse(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "23",
					description = "Pendientes no editadas: las que un cambio de monto vigente reemplaza.")
			int pendingNotManual,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2",
					description = "Pendientes editadas a mano: un cambio de monto vigente no las toca y siguen "
							+ "editadas.")
			int pendingManual) {

		static EntryCountsResponse from(EntryCounts counts) {
			return new EntryCountsResponse(counts.pendingNotManual(), counts.pendingManual());
		}
	}

	static BudgetItemDetailResponse from(Detail detail) {
		BudgetItem item = detail.item();
		return new BudgetItemDetailResponse(item.getId(), item.getName(), item.getKind(),
				item.getDefaultAccount().getId(), detail.currency(),
				item.getCategory() == null ? null : item.getCategory().getId(), item.getPeriodicity(),
				item.getDueDay(), item.getDueMonthOffset(), item.getStartPeriod(), item.getEndPeriod(),
				item.getInstallmentsTotal(), item.getFirstInstallmentNumber(), item.getEstimationRule(),
				item.getCurrentAmount(), Editability.locked(), EntryCountsResponse.from(detail.counts()));
	}
}
