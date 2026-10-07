package com.smartcoin.budgetitem.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Vencimiento de una partida (RN-12): en el mes del período más el desfase, el día de vencimiento o, si ese mes no
 * lo tiene, su último día. Regla pura: recibe valores y devuelve resultados.
 */
public final class DueDateCalculator {

	private DueDateCalculator() {
	}

	/**
	 * @param period período de la partida
	 * @param dueDay día de vencimiento, 1 a 31
	 * @param dueMonthOffset desfase de mes: 0 o −1
	 */
	public static LocalDate dueDate(YearMonth period, int dueDay, int dueMonthOffset) {
		YearMonth month = period.plusMonths(dueMonthOffset);
		return month.atDay(Math.min(dueDay, month.lengthOfMonth()));
	}
}
