package com.smartcoin.budgetitem.domain;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.smartcoin.shared.domain.EntryKind;

/**
 * Los datos con los que se crea un Concepto (RN-10), sin JPA. La categoría y el fin son opcionales. Las cuotas
 * (RN-14) también: sin ellas es un Concepto recurrente común, y con ellas el fin no se informa.
 */
public record BudgetItemValues(String name, EntryKind kind, Long defaultAccountId, Long categoryId,
		Periodicity periodicity, int dueDay, int dueMonthOffset, YearMonth startPeriod, YearMonth endPeriod,
		EstimationRule estimationRule, BigDecimal currentAmount, Integer installmentsTotal,
		Integer firstInstallmentNumber) {

	/** Un Concepto recurrente sin cuotas. */
	public BudgetItemValues(String name, EntryKind kind, Long defaultAccountId, Long categoryId,
			Periodicity periodicity, int dueDay, int dueMonthOffset, YearMonth startPeriod, YearMonth endPeriod,
			EstimationRule estimationRule, BigDecimal currentAmount) {
		this(name, kind, defaultAccountId, categoryId, periodicity, dueDay, dueMonthOffset, startPeriod, endPeriod,
				estimationRule, currentAmount, null, null);
	}
}
