package com.smartcoin.budgetitem.domain;

import java.time.YearMonth;

/**
 * Plan de cuotas de un Concepto (RN-14): un total n y la primera cuota f, la que corresponde al período de inicio.
 * Regla pura: recibe valores y devuelve resultados. El plan avanza al ritmo de la periodicidad, así que una cuota por
 * paso: en un plan bimestral cada cuota cae dos meses después de la anterior.
 *
 * @param total n, entre 1 y {@link #MAX_TOTAL}
 * @param first f, entre 1 y {@code total}
 */
public record InstallmentPlan(int total, int first) {

	/**
	 * Máximo de cuotas de un plan (D-25). El tope de {@code SMALLINT} (32.767) dejaría que un plan anual terminara
	 * después del año 9999, que no entra en {@code end_period CHAR(7)}.
	 */
	public static final int MAX_TOTAL = 360;

	public InstallmentPlan {
		if (total < 1 || total > MAX_TOTAL) {
			throw new IllegalArgumentException("El total de cuotas debe estar entre 1 y " + MAX_TOTAL + ": " + total);
		}
		if (first < 1 || first > total) {
			throw new IllegalArgumentException(
					"La primera cuota debe estar entre 1 y el total (" + total + "): " + first);
		}
	}

	/**
	 * Número de cuota de la k-ésima partida del Concepto, contando desde el período de inicio (k = 0 es la primera).
	 * El k sale del calendario ({@link ScheduleCalculator.ScheduledPeriod#index()}), no de cuántas partidas ya
	 * existen: así las que se generen más adelante, al avanzar el horizonte, continúan la numeración.
	 */
	public int installmentNumber(int index) {
		if (index < 0 || index > total - first) {
			throw new IllegalArgumentException(
					"La partida " + index + " está fuera del plan: va de 0 a " + (total - first) + ".");
		}
		return first + index;
	}

	/** Período de la última cuota: {@code inicio + (n − f) × paso}. */
	public YearMonth endPeriod(YearMonth start, Periodicity periodicity) {
		return start.plusMonths((long) (total - first) * periodicity.months());
	}
}
