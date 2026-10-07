package com.smartcoin.budgetitem.web;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.BudgetItemStatus;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.service.BudgetItemService.ListRow;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BudgetItemListItem", description = "Un Concepto en la lista (HU-14). El estado y las cuotas se "
		+ "calculan en el backend contra el período actual (D-27).")
public record BudgetItemListItemResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "31")
		Long id,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Monotributo")
		String name,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		EntryKind kind,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
		Long defaultAccountId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
		String defaultAccountName,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Moneda del Concepto: la de su cuenta por defecto (RN-04).")
		Currency currency,

		@Schema(nullable = true, example = "3")
		Long categoryId,

		@Schema(nullable = true, example = "Impuestos")
		String categoryName,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		Periodicity periodicity,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "20")
		Integer dueDay,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
		Integer dueMonthOffset,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		EstimationRule estimationRule,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "85000.00")
		BigDecimal currentAmount,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10")
		YearMonth startPeriod,

		@Schema(nullable = true, type = "string", example = "2027-06",
				description = "Último período posible. En un Concepto en cuotas, el de la última cuota.")
		YearMonth endPeriod,

		@Schema(nullable = true, example = "12", description = "Total de cuotas, o nulo si no es en cuotas (RN-14).")
		Integer installmentsTotal,

		@Schema(nullable = true, example = "4",
				description = "Cuota que corresponde al período de inicio, o nulo si no es en cuotas.")
		Integer firstInstallmentNumber,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "ACTIVE: ya empezó y no terminó. FINISHED: su fin es anterior al período actual. "
						+ "SCHEDULED: empieza después del período actual.")
		BudgetItemStatus status,

		@Schema(nullable = true, example = "4",
				description = "Cuota que corresponde al período actual. Solo en un Concepto en cuotas con estado "
						+ "ACTIVE; nulo en los demás casos.")
		Integer currentInstallment,

		@Schema(nullable = true, example = "8",
				description = "Cuotas posteriores a la actual (0 en la última). Solo en un Concepto en cuotas con "
						+ "estado ACTIVE; nulo en los demás casos.")
		Integer installmentsRemaining) {

	static BudgetItemListItemResponse from(ListRow row) {
		BudgetItem item = row.item();
		return new BudgetItemListItemResponse(item.getId(), item.getName(), item.getKind(), row.accountId(),
				row.accountName(), row.currency(), row.categoryId(), row.categoryName(), item.getPeriodicity(),
				item.getDueDay(), item.getDueMonthOffset(), item.getEstimationRule(), item.getCurrentAmount(),
				item.getStartPeriod(), item.getEndPeriod(), item.getInstallmentsTotal(),
				item.getFirstInstallmentNumber(), row.state().status(), row.state().currentInstallment(),
				row.state().installmentsRemaining());
	}
}
