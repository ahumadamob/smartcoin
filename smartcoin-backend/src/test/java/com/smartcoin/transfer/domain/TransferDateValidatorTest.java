package com.smartcoin.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import com.smartcoin.transfer.domain.TransferDateValidator.MonthState;
import com.smartcoin.transfer.domain.TransferDateValidator.Outcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * RN-36: la fecha de una transferencia, en sus bordes. Sin Spring ni base; «hoy» se pasa como valor, igual que lo
 * hace el servicio con su {@code Clock} fijo.
 */
class TransferDateValidatorTest {

	static Outcome validate(String date, String sourceOpening, String targetOpening, String today,
			MonthState month) {
		return TransferDateValidator.validate(LocalDate.parse(date), LocalDate.parse(sourceOpening),
				LocalDate.parse(targetOpening), LocalDate.parse(today), month);
	}

	@ParameterizedTest(name = "hoy {3}: {0} (aperturas {1} y {2}) -> {4}")
	@CsvSource({
			// Hoy es válido; mañana no (S-09).
			"2026-10-15, 2026-08-01, 2026-08-01, 2026-10-15, VALID",
			"2026-10-16, 2026-08-01, 2026-08-01, 2026-10-15, FUTURE",
			// El día de apertura de cada cuenta es válido y el anterior no, con aperturas en fechas distintas.
			"2026-09-10, 2026-09-10, 2026-08-01, 2026-10-15, VALID",
			"2026-09-09, 2026-09-10, 2026-08-01, 2026-10-15, BEFORE_SOURCE_OPENING",
			"2026-09-20, 2026-08-01, 2026-09-20, 2026-10-15, VALID",
			"2026-09-19, 2026-08-01, 2026-09-20, 2026-10-15, BEFORE_TARGET_OPENING",
			// Con las dos fallando se informa la de origen primero.
			"2026-09-01, 2026-09-10, 2026-09-20, 2026-10-15, BEFORE_SOURCE_OPENING",
			// Entre las dos aperturas: cumple la de origen pero no la de destino.
			"2026-09-15, 2026-09-10, 2026-09-20, 2026-10-15, BEFORE_TARGET_OPENING",
			// Futura y anterior a la apertura a la vez: se informa la futura (es la primera).
			"2026-10-20, 2026-10-18, 2026-10-18, 2026-10-15, FUTURE",
			// Una cuenta abierta hoy admite transferencias de hoy.
			"2026-10-15, 2026-10-15, 2026-10-15, 2026-10-15, VALID",
	})
	void theDateIsCheckedAgainstTodayAndBothOpenings(String date, String sourceOpening, String targetOpening,
			String today, Outcome expected) {
		assertThat(validate(date, sourceOpening, targetOpening, today, MonthState.OPEN)).isEqualTo(expected);
	}

	@Test
	void aDateInAClosedMonthIsPeriodClosed() {
		Outcome outcome = validate("2026-09-20", "2026-08-01", "2026-08-01", "2026-10-15", MonthState.CLOSED);

		assertThat(outcome).isEqualTo(Outcome.MONTH_CLOSED);
		assertThat(outcome.isPeriodClosed()).isTrue();
	}

	@Test
	void aMonthWithoutPeriodIsOutOfRangeNotClosed() {
		Outcome outcome = validate("2026-09-20", "2026-08-01", "2026-08-01", "2026-10-15", MonthState.NO_PERIOD);

		assertThat(outcome).isEqualTo(Outcome.MONTH_WITHOUT_PERIOD);
		assertThat(outcome.isPeriodClosed()).isFalse();
	}

	@Test
	void theOutOfRangeFailuresComeBeforeTheClosedMonth() {
		assertThat(validate("2026-10-16", "2026-08-01", "2026-08-01", "2026-10-15", MonthState.CLOSED))
				.isEqualTo(Outcome.FUTURE);
		assertThat(validate("2026-07-31", "2026-08-01", "2026-08-01", "2026-10-15", MonthState.CLOSED))
				.isEqualTo(Outcome.BEFORE_SOURCE_OPENING);
	}

	@ParameterizedTest
	@EnumSource(value = Outcome.class, names = "MONTH_CLOSED", mode = EnumSource.Mode.EXCLUDE)
	void onlyTheClosedMonthIsPeriodClosed(Outcome outcome) {
		assertThat(outcome.isPeriodClosed()).isFalse();
	}

	@Test
	void earliestDateIsTheLatestOpening() {
		assertThat(TransferDateValidator.earliestDate(LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-20")))
				.isEqualTo(LocalDate.parse("2026-09-20"));
		assertThat(TransferDateValidator.earliestDate(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-20")))
				.isEqualTo(LocalDate.parse("2026-09-30"));
	}

	@Test
	void thereIsNoEarlyWindowForTransfers() {
		// Un movimiento de una partida de noviembre admite el 21/10; una transferencia no tiene ventana: solo hoy.
		assertThat(validate("2026-10-16", "2026-08-01", "2026-08-01", "2026-10-15", MonthState.OPEN))
				.isEqualTo(Outcome.FUTURE);
	}
}
