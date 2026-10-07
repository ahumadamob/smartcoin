package com.smartcoin.budgetitem.domain;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.smartcoin.shared.domain.EntryKind;

/** Los datos con los que se crea un Concepto recurrente (RN-10), sin JPA. La categoría y el fin son opcionales. */
public record BudgetItemValues(String name, EntryKind kind, Long defaultAccountId, Long categoryId,
		Periodicity periodicity, int dueDay, int dueMonthOffset, YearMonth startPeriod, YearMonth endPeriod,
		EstimationRule estimationRule, BigDecimal currentAmount) {
}
