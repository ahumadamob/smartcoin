package com.smartcoin.entry.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Rango del vencimiento de una partida sin Concepto (RN-19): entre el primer día del mes anterior al período y el
 * último día del período, ambos inclusive. Regla pura: recibe valores y devuelve resultados. No depende de «hoy»: un
 * vencimiento anterior a hoy es válido y la partida nace vencida (RN-20).
 */
public final class EntryDueDateRange {

	private EntryDueDateRange() {
	}

	/** El primer vencimiento válido: el día 1 del mes anterior al período (diciembre del año anterior, en enero). */
	public static LocalDate earliest(YearMonth period) {
		return period.minusMonths(1).atDay(1);
	}

	/** El último vencimiento válido: el último día del período. */
	public static LocalDate latest(YearMonth period) {
		return period.atEndOfMonth();
	}

	public static boolean contains(YearMonth period, LocalDate dueDate) {
		return !dueDate.isBefore(earliest(period)) && !dueDate.isAfter(latest(period));
	}
}
