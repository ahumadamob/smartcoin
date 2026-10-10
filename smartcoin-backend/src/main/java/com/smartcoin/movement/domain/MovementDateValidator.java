package com.smartcoin.movement.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Fecha de un movimiento (RN-21, condición 4). Regla pura: recibe valores y devuelve resultados; no sabe de Spring ni
 * de la base. La fecha debe cumplir, en este orden:
 *
 * <ol>
 * <li>ser igual o posterior al primer día del período de la partida menos la ventana de anticipación (D-17);</li>
 * <li>ser igual o posterior a la fecha de apertura de la cuenta del movimiento (S-13);</li>
 * <li>no ser posterior a hoy (S-09);</li>
 * <li>caer en un mes que sea un período abierto (RN-09).</li>
 * </ol>
 *
 * <p>Se informa la primera que falla (D-33). Las tres primeras son {@code DATE_OUT_OF_RANGE}; la última, {@code
 * PERIOD_CLOSED}, salvo que el mes no exista para el usuario (es anterior a su período inicial): ese no está cerrado
 * sino fuera de rango, y responde {@code DATE_OUT_OF_RANGE}. Hoy no se puede producir desde la API, porque la
 * apertura de la cuenta nunca es anterior al primer día del período inicial (RN-33).
 *
 * <p>No depende de la moneda, del tipo ni del estado de la partida: eso lo controla el servicio antes.
 */
public final class MovementDateValidator {

	/** Estado del mes de la fecha, según los períodos que existen para el usuario. */
	public enum MonthState {
		OPEN, CLOSED,
		/** Es anterior al período inicial del usuario o posterior a su horizonte (RN-06). */
		NO_PERIOD
	}

	/** Qué condición falló, o {@link #VALID}. */
	public enum Outcome {
		VALID, BEFORE_WINDOW, BEFORE_ACCOUNT_OPENING, FUTURE, MONTH_WITHOUT_PERIOD, MONTH_CLOSED;

		public boolean isValid() {
			return this == VALID;
		}

		/** {@code true} si el código es {@code PERIOD_CLOSED}; las demás fallas son {@code DATE_OUT_OF_RANGE}. */
		public boolean isPeriodClosed() {
			return this == MONTH_CLOSED;
		}
	}

	private MovementDateValidator() {
	}

	/** La fecha más temprana que admite una partida del período: su primer día menos la ventana (D-17). */
	public static LocalDate windowStart(YearMonth entryPeriod, int earlyDays) {
		return entryPeriod.atDay(1).minusDays(earlyDays);
	}

	/**
	 * La fecha más temprana que admite una partida del período con la cuenta dada: la más tardía entre el inicio de la
	 * ventana (D-17) y la apertura de la cuenta (S-13). Sale de las mismas dos condiciones que {@link #validate}, y
	 * por eso una fecha las cumple si y solo si no es anterior a ésta. No mira «hoy» ni el estado del mes: si es
	 * posterior a hoy, la partida todavía no admite ningún movimiento.
	 */
	public static LocalDate earliestDate(YearMonth entryPeriod, LocalDate accountOpening, int earlyDays) {
		LocalDate windowStart = windowStart(entryPeriod, earlyDays);
		return accountOpening.isAfter(windowStart) ? accountOpening : windowStart;
	}

	/**
	 * @param date           fecha del movimiento
	 * @param entryPeriod    período de la partida
	 * @param accountOpening fecha de apertura de la cuenta del movimiento, que puede no ser la de la partida (S-02)
	 * @param today          hoy, del {@code Clock} (RN-02)
	 * @param earlyDays      ventana de anticipación, en días ({@code app.budget.early-days})
	 * @param monthOfDate    estado del mes de {@code date}
	 */
	public static Outcome validate(LocalDate date, YearMonth entryPeriod, LocalDate accountOpening, LocalDate today,
			int earlyDays, MonthState monthOfDate) {
		if (date.isBefore(windowStart(entryPeriod, earlyDays))) {
			return Outcome.BEFORE_WINDOW;
		}
		if (date.isBefore(accountOpening)) {
			return Outcome.BEFORE_ACCOUNT_OPENING;
		}
		if (date.isAfter(today)) {
			return Outcome.FUTURE;
		}
		return switch (monthOfDate) {
			case OPEN -> Outcome.VALID;
			case CLOSED -> Outcome.MONTH_CLOSED;
			case NO_PERIOD -> Outcome.MONTH_WITHOUT_PERIOD;
		};
	}
}
