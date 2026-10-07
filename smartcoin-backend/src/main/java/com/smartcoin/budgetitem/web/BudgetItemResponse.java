package com.smartcoin.budgetitem.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.service.BudgetItemService.Created;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Un Concepto recién creado, con el resumen de las partidas que generó. En un Concepto en cuotas, "
		+ "`endPeriod` es el período de la última cuota, calculado (RN-14).")
public record BudgetItemResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "31")
		Long id,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Monotributo")
		String name,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		EntryKind kind,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
		Long defaultAccountId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Moneda del Concepto: la de su cuenta por defecto (RN-04).")
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
				description = "Partidas generadas al crear el Concepto (RN-13).")
		Generation generation) {

	@Schema(name = "BudgetItemGeneration",
			description = "Resumen de las partidas que generó el alta: una por período que corresponde, desde el "
					+ "inicio hasta el horizonte o el fin. Si el plan de cuotas termina después del horizonte, solo "
					+ "se generan las cuotas que entran; las demás se generan al avanzar el horizonte.")
	public record Generation(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "25")
			int entryCount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10",
					description = "Período de la primera partida generada.")
			YearMonth firstPeriod,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2028-10",
					description = "Período de la última partida generada.")
			YearMonth lastPeriod,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-20",
					description = "Vencimiento de la primera partida (RN-12). Con desfase −1 cae en el mes anterior a "
							+ "su período.")
			LocalDate firstDueDate,

			@Schema(nullable = true, example = "4",
					description = "Número de cuota de la primera partida generada. Nulo si no es en cuotas.")
			Integer firstInstallment,

			@Schema(nullable = true, example = "12",
					description = "Número de cuota de la última partida generada. Es igual al total de cuotas si el "
							+ "plan se generó completo; menor si termina después del horizonte. Nulo si no es en "
							+ "cuotas.")
			Integer lastInstallment) {

		/** El alta siempre genera al menos la partida del período de inicio. */
		static Generation of(List<BudgetEntry> entries) {
			BudgetEntry first = entries.getFirst();
			BudgetEntry last = entries.getLast();
			return new Generation(entries.size(), first.getPeriod().getPeriodMonth(),
					last.getPeriod().getPeriodMonth(), first.getDueDate(), first.getInstallmentNumber(),
					last.getInstallmentNumber());
		}
	}

	static BudgetItemResponse from(Created created) {
		BudgetItem item = created.item();
		return new BudgetItemResponse(item.getId(), item.getName(), item.getKind(), item.getDefaultAccount().getId(),
				item.getDefaultAccount().getCurrency(), item.getCategory() == null ? null : item.getCategory().getId(),
				item.getPeriodicity(), item.getDueDay(), item.getDueMonthOffset(), item.getStartPeriod(),
				item.getEndPeriod(), item.getInstallmentsTotal(), item.getFirstInstallmentNumber(),
				item.getEstimationRule(), item.getCurrentAmount(),
				Generation.of(created.entries()));
	}
}
