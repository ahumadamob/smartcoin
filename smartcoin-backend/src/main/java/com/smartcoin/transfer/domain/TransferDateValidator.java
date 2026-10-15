package com.smartcoin.transfer.domain;

import java.time.LocalDate;

/**
 * Fecha de una transferencia (RN-36). Regla pura: recibe valores y devuelve resultados; no sabe de Spring ni de la
 * base. La fecha debe cumplir, en este orden:
 *
 * <ol>
 * <li>no ser posterior a hoy (S-09);</li>
 * <li>ser igual o posterior a la fecha de apertura de la cuenta de origen (S-13);</li>
 * <li>ser igual o posterior a la fecha de apertura de la cuenta de destino (S-13);</li>
 * <li>caer en un mes que sea un período abierto (RN-09).</li>
 * </ol>
 *
 * <p>Se informa la primera que falla (D-39). Las tres primeras son {@code DATE_OUT_OF_RANGE}; la última, {@code
 * PERIOD_CLOSED}, salvo que el mes no exista para el usuario (anterior a su período inicial): ese no está cerrado sino
 * fuera de rango. A diferencia de los movimientos (RN-21), no hay ventana de anticipación.
 */
public final class TransferDateValidator {

	/** Estado del mes de la fecha, según los períodos que existen para el usuario. */
	public enum MonthState {
		OPEN, CLOSED,
		/** Es anterior al período inicial del usuario o posterior a su horizonte (RN-06). */
		NO_PERIOD
	}

	/** Qué condición falló, o {@link #VALID}. */
	public enum Outcome {
		VALID, FUTURE, BEFORE_SOURCE_OPENING, BEFORE_TARGET_OPENING, MONTH_WITHOUT_PERIOD, MONTH_CLOSED;

		public boolean isValid() {
			return this == VALID;
		}

		/** {@code true} si el código es {@code PERIOD_CLOSED}; las demás fallas son {@code DATE_OUT_OF_RANGE}. */
		public boolean isPeriodClosed() {
			return this == MONTH_CLOSED;
		}
	}

	private TransferDateValidator() {
	}

	/**
	 * La fecha más temprana que admite una transferencia entre dos cuentas: la más tardía de sus aperturas. No mira
	 * «hoy» ni el estado del mes.
	 */
	public static LocalDate earliestDate(LocalDate sourceOpening, LocalDate targetOpening) {
		return sourceOpening.isAfter(targetOpening) ? sourceOpening : targetOpening;
	}

	/**
	 * @param date          fecha de la transferencia
	 * @param sourceOpening fecha de apertura de la cuenta de origen
	 * @param targetOpening fecha de apertura de la cuenta de destino
	 * @param today         hoy, del {@code Clock} (RN-02)
	 * @param monthOfDate   estado del mes de {@code date}
	 */
	public static Outcome validate(LocalDate date, LocalDate sourceOpening, LocalDate targetOpening, LocalDate today,
			MonthState monthOfDate) {
		if (date.isAfter(today)) {
			return Outcome.FUTURE;
		}
		if (date.isBefore(sourceOpening)) {
			return Outcome.BEFORE_SOURCE_OPENING;
		}
		if (date.isBefore(targetOpening)) {
			return Outcome.BEFORE_TARGET_OPENING;
		}
		return switch (monthOfDate) {
			case OPEN -> Outcome.VALID;
			case CLOSED -> Outcome.MONTH_CLOSED;
			case NO_PERIOD -> Outcome.MONTH_WITHOUT_PERIOD;
		};
	}
}
