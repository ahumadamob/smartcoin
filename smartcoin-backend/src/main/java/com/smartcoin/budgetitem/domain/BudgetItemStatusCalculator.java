package com.smartcoin.budgetitem.domain;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Estado de un Concepto y, si es en cuotas y está activo, su cuota actual y cuántas quedan (D-27, RN-14). Regla pura:
 * se calcula por calendario con el período actual, sin mirar las partidas.
 */
public final class BudgetItemStatusCalculator {

	/**
	 * @param currentInstallment    cuota actual; solo si es en cuotas y está activo
	 * @param installmentsRemaining cuotas posteriores a la actual; solo si es en cuotas y está activo
	 */
	public record Result(BudgetItemStatus status, Integer currentInstallment, Integer installmentsRemaining) {
	}

	private BudgetItemStatusCalculator() {
	}

	/**
	 * @param start       período de inicio
	 * @param end         período de fin (en un plan de cuotas, el calculado), o {@code null}
	 * @param periodicity periodicidad (el paso entre cuotas)
	 * @param plan        plan de cuotas, o {@code null}
	 * @param current     período actual, del {@code Clock}
	 */
	public static Result calculate(YearMonth start, YearMonth end, Periodicity periodicity, InstallmentPlan plan,
			YearMonth current) {
		if (end != null && end.isBefore(current)) {
			return new Result(BudgetItemStatus.FINISHED, null, null);
		}
		if (start.isAfter(current)) {
			return new Result(BudgetItemStatus.SCHEDULED, null, null);
		}
		if (plan == null) {
			return new Result(BudgetItemStatus.ACTIVE, null, null);
		}
		long steps = ChronoUnit.MONTHS.between(start, current) / periodicity.months();
		int currentInstallment = (int) Math.min(plan.total(), plan.first() + steps);
		return new Result(BudgetItemStatus.ACTIVE, currentInstallment, plan.total() - currentInstallment);
	}
}
