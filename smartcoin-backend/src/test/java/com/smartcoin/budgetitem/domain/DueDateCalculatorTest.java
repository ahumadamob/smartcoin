package com.smartcoin.budgetitem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RN-12: la tabla de ejemplos de la regla y los casos límite de febrero, año bisiesto y cambio de año. */
class DueDateCalculatorTest {

	@ParameterizedTest(name = "{0}: día {1}, desfase {2}, período {3} → {4}")
	@CsvSource({
			"Monotributo, 20,  0, 2026-10, 2026-10-20",
			"Alquiler,    31,  0, 2026-11, 2026-11-30",
			"Alquiler,    31,  0, 2027-02, 2027-02-28",
			"Alquiler,    29,  0, 2028-02, 2028-02-29",
			"Sueldo A,    25, -1, 2026-12, 2026-11-25",
			"Sueldo B,    30, -1, 2026-12, 2026-11-30",
			"Sueldo B,    30, -1, 2027-03, 2027-02-28",
			"Sueldo A,    25, -1, 2027-01, 2026-12-25",
	})
	void examplesOfTheRule(String item, int dueDay, int offset, YearMonth period, LocalDate expected) {
		assertThat(DueDateCalculator.dueDate(period, dueDay, offset)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "día {0} en {1} → {2}")
	@CsvSource({
			"28, 2027-02, 2027-02-28",
			"29, 2027-02, 2027-02-28",
			"30, 2027-02, 2027-02-28",
			"31, 2027-02, 2027-02-28",
			"28, 2028-02, 2028-02-28",
			"29, 2028-02, 2028-02-29",
			"30, 2028-02, 2028-02-29",
			"31, 2028-02, 2028-02-29",
	})
	void februaryInCommonAndLeapYears(int dueDay, YearMonth period, LocalDate expected) {
		assertThat(DueDateCalculator.dueDate(period, dueDay, 0)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "día {0}, período {1} con desfase −1 → {2}")
	@CsvSource({
			"29, 2027-03, 2027-02-28",
			"31, 2027-03, 2027-02-28",
			"29, 2028-03, 2028-02-29",
			"30, 2028-03, 2028-02-29",
			"31, 2028-03, 2028-02-29",
	})
	void offsetIntoFebruaryUsesTheLastDayOfFebruary(int dueDay, YearMonth period, LocalDate expected) {
		assertThat(DueDateCalculator.dueDate(period, dueDay, -1)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "día {0}, período {1} con desfase −1 → {2}")
	@CsvSource({
			" 1, 2027-01, 2026-12-01",
			"25, 2027-01, 2026-12-25",
			"31, 2027-01, 2026-12-31",
			"31, 2028-01, 2027-12-31",
	})
	void offsetInJanuaryFallsInDecemberOfThePreviousYear(int dueDay, YearMonth period, LocalDate expected) {
		assertThat(DueDateCalculator.dueDate(period, dueDay, -1)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "día {0} en {1} → {2}")
	@CsvSource({
			" 1, 2026-10, 2026-10-01",
			"31, 2026-10, 2026-10-31",
			"31, 2026-09, 2026-09-30",
			"30, 2026-09, 2026-09-30",
	})
	void firstAndLastDaysOfTheMonth(int dueDay, YearMonth period, LocalDate expected) {
		assertThat(DueDateCalculator.dueDate(period, dueDay, 0)).isEqualTo(expected);
	}
}
