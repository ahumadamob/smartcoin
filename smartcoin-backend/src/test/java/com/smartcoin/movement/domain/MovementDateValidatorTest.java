package com.smartcoin.movement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import com.smartcoin.movement.domain.MovementDateValidator.MonthState;
import com.smartcoin.movement.domain.MovementDateValidator.Outcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * RN-21, condición 4: la fecha de un movimiento, en sus bordes. Sin Spring ni base; el «hoy» y la ventana se pasan
 * como valores, igual que lo hace el servicio con su {@code Clock}.
 */
class MovementDateValidatorTest {

	static final LocalDate LONG_AGO = LocalDate.parse("2026-08-01");

	static Outcome validate(String date, String period, String opening, String today, int earlyDays,
			MonthState month) {
		return MovementDateValidator.validate(LocalDate.parse(date), YearMonth.parse(period),
				LocalDate.parse(opening), LocalDate.parse(today), earlyDays, month);
	}

	@ParameterizedTest(name = "ventana de {4} días, período {1}: {0} -> {5}")
	@CsvSource({
			// Ejemplo de RN-21 y HU-20: sueldo de diciembre, ventana de 10 días. El límite exacto es el 21/11.
			"2026-11-21, 2026-12, 2026-08-01, 2026-12-15, 10, VALID",
			"2026-11-20, 2026-12, 2026-08-01, 2026-12-15, 10, BEFORE_WINDOW",
			"2026-11-25, 2026-12, 2026-08-01, 2026-12-15, 10, VALID",
			"2026-11-30, 2026-12, 2026-08-01, 2026-12-15, 10, VALID",
			// Dentro del propio período y en el mes posterior (se pagó tarde, S-24).
			"2026-12-01, 2026-12, 2026-08-01, 2026-12-15, 10, VALID",
			"2027-01-02, 2026-12, 2026-08-01, 2027-01-05, 10, VALID",
			// La ventana cruza de año: para enero de 2027 el límite es el 22/12/2026.
			"2026-12-22, 2027-01, 2026-08-01, 2027-01-15, 10, VALID",
			"2026-12-21, 2027-01, 2026-08-01, 2027-01-15, 10, BEFORE_WINDOW",
			// Ventana de otro valor, porque es configurable: 0 (sin anticipación), 3 y 31 días.
			"2026-12-01, 2026-12, 2026-08-01, 2026-12-15, 0, VALID",
			"2026-11-30, 2026-12, 2026-08-01, 2026-12-15, 0, BEFORE_WINDOW",
			"2026-11-28, 2026-12, 2026-08-01, 2026-12-15, 3, VALID",
			"2026-11-27, 2026-12, 2026-08-01, 2026-12-15, 3, BEFORE_WINDOW",
			"2026-10-31, 2026-12, 2026-08-01, 2026-12-15, 31, VALID",
			"2026-10-30, 2026-12, 2026-08-01, 2026-12-15, 31, BEFORE_WINDOW",
			// Marzo: la ventana atraviesa febrero, bisiesto o no.
			"2028-02-20, 2028-03, 2026-08-01, 2028-03-15, 10, VALID",
			"2028-02-19, 2028-03, 2026-08-01, 2028-03-15, 10, BEFORE_WINDOW",
			"2027-02-19, 2027-03, 2026-08-01, 2027-03-15, 10, VALID",
			"2027-02-18, 2027-03, 2026-08-01, 2027-03-15, 10, BEFORE_WINDOW",
	})
	void theWindowStartsTheConfiguredDaysBeforeThePeriod(String date, String period, String opening, String today,
			int earlyDays, Outcome expected) {
		assertThat(validate(date, period, opening, today, earlyDays, MonthState.OPEN)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "hoy {3}: {0} -> {4}")
	@CsvSource({
			// Hoy es válido, mañana no (S-09). El 10/10/2026 es sábado: no importa, es solo calendario.
			"2026-10-10, 2026-10, 2026-08-01, 2026-10-10, VALID",
			"2026-10-11, 2026-10, 2026-08-01, 2026-10-10, FUTURE",
			"2026-10-09, 2026-10, 2026-08-01, 2026-10-10, VALID",
			// Fin de mes: el 1.º del mes siguiente es futuro, y el 31 del mes no.
			"2026-10-31, 2026-10, 2026-08-01, 2026-10-31, VALID",
			"2026-11-01, 2026-10, 2026-08-01, 2026-10-31, FUTURE",
	})
	void aMovementCannotBeDatedAfterToday(String date, String period, String opening, String today,
			Outcome expected) {
		assertThat(validate(date, period, opening, today, 10, MonthState.OPEN)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "apertura {2}: {0} -> {4}")
	@CsvSource({
			// El día de apertura es válido (el saldo inicial es al comienzo de ese día) y el anterior no.
			"2026-10-05, 2026-10, 2026-10-05, 2026-10-10, VALID",
			"2026-10-04, 2026-10, 2026-10-05, 2026-10-10, BEFORE_ACCOUNT_OPENING",
			// La apertura es posterior al inicio de la ventana: manda la apertura.
			"2026-09-25, 2026-10, 2026-10-01, 2026-10-10, BEFORE_ACCOUNT_OPENING",
			// La apertura es anterior a la ventana: manda la ventana.
			"2026-09-20, 2026-10, 2026-08-01, 2026-10-10, BEFORE_WINDOW",
			"2026-09-21, 2026-10, 2026-08-01, 2026-10-10, VALID",
	})
	void aMovementCannotPrecedeTheAccountOpening(String date, String period, String opening, String today,
			Outcome expected) {
		assertThat(validate(date, period, opening, today, 10, MonthState.OPEN)).isEqualTo(expected);
	}

	@Test
	void aDateInAClosedMonthInsideTheWindowIsPeriodClosed() {
		// HU-20, criterio 4: noviembre cerrado, partida de diciembre, fecha dentro de la ventana.
		Outcome outcome = validate("2026-11-25", "2026-12", "2026-08-01", "2026-12-15", 10, MonthState.CLOSED);

		assertThat(outcome).isEqualTo(Outcome.MONTH_CLOSED);
		assertThat(outcome.isPeriodClosed()).isTrue();
		assertThat(outcome.isValid()).isFalse();
	}

	@Test
	void aDateInAMonthWithoutAPeriodIsOutOfRangeNotClosed() {
		// Un mes anterior al período inicial no existe: no está cerrado, está fuera de rango (RN-06, D-33).
		Outcome outcome = validate("2026-07-30", "2026-08", "2026-07-01", "2026-10-10", 10,
				MonthState.NO_PERIOD);

		assertThat(outcome).isEqualTo(Outcome.MONTH_WITHOUT_PERIOD);
		assertThat(outcome.isPeriodClosed()).isFalse();
		assertThat(outcome.isValid()).isFalse();
	}

	@ParameterizedTest
	@EnumSource(MonthState.class)
	void anOpenMonthIsTheOnlyValidOne(MonthState month) {
		Outcome outcome = validate("2026-10-05", "2026-10", "2026-08-01", "2026-10-10", 10, month);

		assertThat(outcome.isValid()).isEqualTo(month == MonthState.OPEN);
	}

	@Test
	void whenSeveralConditionsFailTheFirstInTheDocumentedOrderIsReported() {
		// Antes de la ventana, antes de la apertura, futura y en un mes cerrado a la vez: ventana primero.
		assertThat(MovementDateValidator.validate(LocalDate.parse("2026-01-01"), YearMonth.parse("2026-10"),
				LocalDate.parse("2026-10-20"), LocalDate.parse("2025-12-31"), 10, MonthState.CLOSED))
				.isEqualTo(Outcome.BEFORE_WINDOW);
		// Antes de la apertura y en un mes cerrado: las tres de rango van antes que PERIOD_CLOSED (RN-21).
		assertThat(validate("2026-09-25", "2026-10", "2026-10-01", "2026-10-10", 10, MonthState.CLOSED))
				.isEqualTo(Outcome.BEFORE_ACCOUNT_OPENING);
		// Futura y en un mes sin período: futura.
		assertThat(validate("2026-10-11", "2026-10", "2026-08-01", "2026-10-10", 10, MonthState.NO_PERIOD))
				.isEqualTo(Outcome.FUTURE);
	}

	@Test
	void aPeriodBeyondTheWindowHasNoValidDateUntilItsWindowOpens() {
		// Hoy 10/10: para diciembre la ventana abre el 21/11, que es posterior a hoy. Ninguna fecha sirve.
		assertThat(validate("2026-10-10", "2026-12", "2026-08-01", "2026-10-10", 10, MonthState.OPEN))
				.isEqualTo(Outcome.BEFORE_WINDOW);
		assertThat(validate("2026-11-21", "2026-12", "2026-08-01", "2026-10-10", 10, MonthState.OPEN))
				.isEqualTo(Outcome.FUTURE);
	}

	@ParameterizedTest(name = "período {0}, ventana {1}: desde {2}")
	@CsvSource({
			"2026-12, 10, 2026-11-21",
			"2027-01, 10, 2026-12-22",
			"2026-12, 0, 2026-12-01",
			"2028-03, 10, 2028-02-20",
			"2027-03, 10, 2027-02-19",
	})
	void exposesTheWindowStart(YearMonth period, int earlyDays, LocalDate expected) {
		assertThat(MovementDateValidator.windowStart(period, earlyDays)).isEqualTo(expected);
	}
}
