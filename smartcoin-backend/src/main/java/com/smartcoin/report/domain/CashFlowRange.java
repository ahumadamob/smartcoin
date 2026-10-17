package com.smartcoin.report.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import com.smartcoin.shared.error.BusinessException;

/**
 * Rango de fechas del flujo de caja (HU-34, D-46). Regla pura: completa los extremos que faltan y verifica los
 * límites.
 *
 * <p>Sin {@code from} ni {@code to}, el mes de hoy; sin {@code to}, el último día del mes de {@code from}; sin
 * {@code from}, el primer día del mes de {@code to}. El rango no puede empezar antes del primer día del período
 * inicial ni terminar después del último día del horizonte, y tiene como máximo {@value #MAX_DAYS} días con ambos
 * extremos incluidos. Un error de rango es {@code VALIDATION_ERROR} (400) con el error en el campo.
 */
public final class CashFlowRange {

	/** Largo máximo del rango, con ambos extremos incluidos: un año. */
	public static final int MAX_DAYS = 366;

	private CashFlowRange() {
	}

	public record Range(LocalDate from, LocalDate to) {
	}

	/**
	 * @param from        desde, o {@code null}
	 * @param to          hasta, o {@code null}
	 * @param today       hoy, del {@code Clock} (RN-02)
	 * @param startPeriod primer período que existe para el usuario
	 * @param horizon     último período que existe para el usuario
	 */
	public static Range resolve(LocalDate from, LocalDate to, LocalDate today, YearMonth startPeriod,
			YearMonth horizon) {
		LocalDate effectiveFrom = from;
		LocalDate effectiveTo = to;
		if (from == null && to == null) {
			YearMonth current = YearMonth.from(today);
			effectiveFrom = current.atDay(1);
			effectiveTo = current.atEndOfMonth();
		}
		else if (to == null) {
			effectiveTo = YearMonth.from(from).atEndOfMonth();
		}
		else if (from == null) {
			effectiveFrom = YearMonth.from(to).atDay(1);
		}

		if (effectiveFrom.isAfter(effectiveTo)) {
			throw BusinessException.invalidField("from", "La fecha desde no puede ser posterior a la fecha hasta.");
		}
		if (effectiveFrom.isBefore(startPeriod.atDay(1))) {
			throw BusinessException.invalidField("from",
					"La fecha desde no puede ser anterior al período inicial (" + startPeriod + ").");
		}
		if (effectiveTo.isAfter(horizon.atEndOfMonth())) {
			throw BusinessException.invalidField("to",
					"La fecha hasta no puede ser posterior al horizonte (" + horizon + ").");
		}
		if (ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1 > MAX_DAYS) {
			throw BusinessException.invalidField("to",
					"El rango no puede tener más de " + MAX_DAYS + " días.");
		}
		return new Range(effectiveFrom, effectiveTo);
	}
}
