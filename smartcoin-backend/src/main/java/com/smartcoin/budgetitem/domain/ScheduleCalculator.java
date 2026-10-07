package com.smartcoin.budgetitem.domain;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Qué períodos le corresponde generar a un Concepto (RN-11 y RN-13): los {@code inicio + k × paso} posteriores a lo
 * ya generado y que no superan ni el horizonte ni el fin. Regla pura: recibe valores y devuelve resultados.
 */
public final class ScheduleCalculator {

	private ScheduleCalculator() {
	}

	/**
	 * Un período con partida. {@code index} es el k de RN-11 (0 para el período de inicio): con él, un Concepto en
	 * cuotas calcula el número de cuota (RN-14).
	 */
	public record ScheduledPeriod(YearMonth period, int index) {
	}

	/** Los períodos a generar, en orden, y hasta dónde queda procesado el Concepto. */
	public record Schedule(List<ScheduledPeriod> periods, YearMonth generatedUntil) {
	}

	/**
	 * @param start período de inicio del Concepto
	 * @param end período de fin, o {@code null} si no tiene
	 * @param generatedUntil último período ya procesado, o {@code null} si todavía no generó
	 * @param horizon último período que existe
	 */
	public static Schedule pending(YearMonth start, Periodicity periodicity, YearMonth end, YearMonth generatedUntil,
			YearMonth horizon) {
		YearMonth limit = end != null && end.isBefore(horizon) ? end : horizon;
		List<ScheduledPeriod> periods = new ArrayList<>();
		int index = 0;
		for (YearMonth period = start; !period.isAfter(limit); period = period.plusMonths(periodicity.months())) {
			// Un período ya procesado no se vuelve a procesar: así una partida eliminada no reaparece.
			if (generatedUntil == null || period.isAfter(generatedUntil)) {
				periods.add(new ScheduledPeriod(period, index));
			}
			index++;
		}
		return new Schedule(List.copyOf(periods), limit);
	}
}
