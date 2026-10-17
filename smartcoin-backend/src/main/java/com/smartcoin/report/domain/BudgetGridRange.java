package com.smartcoin.report.domain;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import com.smartcoin.shared.error.BusinessException;

/**
 * Rango de meses de la vista de varios meses (HU-36, D-48). Regla pura: completa los extremos que faltan y verifica
 * los límites.
 *
 * <p>Sin {@code from} ni {@code to}, {@value #DEFAULT_MONTHS} meses desde el actual; sin {@code to}, esos meses desde
 * {@code from} sin pasar del horizonte; sin {@code from}, esos meses hasta {@code to} sin bajar del período inicial. El
 * rango no puede empezar antes del período inicial ni terminar después del horizonte, y tiene como máximo
 * {@value #MAX_MONTHS} meses con ambos extremos incluidos. Un error de rango es {@code VALIDATION_ERROR} (400) con el
 * error en el campo.
 */
public final class BudgetGridRange {

	public static final int DEFAULT_MONTHS = 12;
	public static final int MAX_MONTHS = 24;

	private BudgetGridRange() {
	}

	public record Range(YearMonth from, YearMonth to) {
	}

	/**
	 * @param from        desde, o {@code null}
	 * @param to          hasta, o {@code null}
	 * @param current     período actual, del {@code Clock} (RN-05)
	 * @param startPeriod primer período que existe para el usuario
	 * @param horizon     último período que existe para el usuario
	 */
	public static Range resolve(YearMonth from, YearMonth to, YearMonth current, YearMonth startPeriod,
			YearMonth horizon) {
		YearMonth effectiveFrom = from;
		YearMonth effectiveTo = to;
		if (from == null && to == null) {
			effectiveFrom = current;
			effectiveTo = current.plusMonths(DEFAULT_MONTHS - 1);
		}
		else if (to == null) {
			effectiveTo = min(from.plusMonths(DEFAULT_MONTHS - 1), horizon);
		}
		else if (from == null) {
			effectiveFrom = max(to.minusMonths(DEFAULT_MONTHS - 1), startPeriod);
		}

		if (effectiveFrom.isAfter(effectiveTo)) {
			throw BusinessException.invalidField("from", "El mes desde no puede ser posterior al mes hasta.");
		}
		if (effectiveFrom.isBefore(startPeriod)) {
			throw BusinessException.invalidField("from",
					"El mes desde no puede ser anterior al período inicial (" + startPeriod + ").");
		}
		if (effectiveTo.isAfter(horizon)) {
			throw BusinessException.invalidField("to",
					"El mes hasta no puede ser posterior al horizonte (" + horizon + ").");
		}
		if (ChronoUnit.MONTHS.between(effectiveFrom, effectiveTo) + 1 > MAX_MONTHS) {
			throw BusinessException.invalidField("to", "El rango no puede tener más de " + MAX_MONTHS + " meses.");
		}
		return new Range(effectiveFrom, effectiveTo);
	}

	private static YearMonth min(YearMonth a, YearMonth b) {
		return a.isBefore(b) ? a : b;
	}

	private static YearMonth max(YearMonth a, YearMonth b) {
		return a.isAfter(b) ? a : b;
	}
}
