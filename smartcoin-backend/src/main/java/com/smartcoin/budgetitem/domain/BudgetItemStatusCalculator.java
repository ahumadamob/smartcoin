package com.smartcoin.budgetitem.domain;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Estado de un Concepto y, si es en cuotas y está activo, su cuota actual y cuántas quedan (D-27, RN-14). Regla pura:
 * se calcula por calendario con el período actual, sin mirar las partidas.
 *
 * <p>Las cuotas que quedan llegan hasta la última que sigue en el plan: la del período de fin guardado. Si el plan
 * se recortó al eliminar «Este mes y los siguientes» (RN-31, HU-18), el fin es anterior al calculado y las cuotas
 * eliminadas ya no cuentan. El total no cambia: es el del plan original.
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
		return new Result(BudgetItemStatus.ACTIVE, currentInstallment,
				lastInstallment(start, end, periodicity, plan) - currentInstallment);
	}

	/**
	 * Número de la última cuota que sigue en el plan. Sin recortar, el fin es {@code inicio + (n − f) × paso} y da el
	 * total; recortado, da la cuota del último período que queda.
	 */
	private static int lastInstallment(YearMonth start, YearMonth end, Periodicity periodicity, InstallmentPlan plan) {
		if (end == null) {
			return plan.total();
		}
		long steps = ChronoUnit.MONTHS.between(start, end) / periodicity.months();
		return (int) Math.min(plan.total(), plan.first() + steps);
	}
}
