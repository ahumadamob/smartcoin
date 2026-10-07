package com.smartcoin.period.domain;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Rango de períodos de un usuario (RN-06): desde su período inicial hasta el horizonte, que es el período
 * actual más {@code horizonMonths}. Regla pura: recibe valores y devuelve resultados.
 */
public final class PeriodRange {

	private PeriodRange() {
	}

	/** Último período que existe. */
	public static YearMonth horizon(YearMonth current, int horizonMonths) {
		return current.plusMonths(horizonMonths);
	}

	/**
	 * Primer período abierto (RN-08): el siguiente al último cerrado, o el período inicial si no hay ninguno cerrado.
	 *
	 * @param lastClosed último período cerrado, o {@code null} si no hay ninguno
	 */
	public static YearMonth firstOpen(YearMonth start, YearMonth lastClosed) {
		return lastClosed == null ? start : lastClosed.plusMonths(1);
	}

	/** Todos los períodos que deben existir, en orden. Vacío si el inicio supera el horizonte. */
	public static List<YearMonth> required(YearMonth start, YearMonth current, int horizonMonths) {
		YearMonth last = horizon(current, horizonMonths);
		List<YearMonth> periods = new ArrayList<>();
		for (YearMonth month = start; !month.isAfter(last); month = month.plusMonths(1)) {
			periods.add(month);
		}
		return periods;
	}

	/** Los períodos que deben existir y todavía no (RN-07: asegurar el horizonte es idempotente). */
	public static List<YearMonth> missing(YearMonth start, YearMonth current, int horizonMonths,
			Collection<YearMonth> existing) {
		return required(start, current, horizonMonths).stream()
				.filter(month -> !existing.contains(month))
				.toList();
	}
}
