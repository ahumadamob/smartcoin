package com.smartcoin.budgetitem.domain;

/** Estado de un Concepto respecto del período actual (D-27). */
public enum BudgetItemStatus {
	/** Ya empezó y no terminó. */
	ACTIVE,
	/** Su fin es anterior al período actual. */
	FINISHED,
	/** Su inicio es posterior al período actual. */
	SCHEDULED
}
