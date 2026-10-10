package com.smartcoin.entry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RN-19: el rango del vencimiento de una partida sin Concepto, en sus bordes. */
class EntryDueDateRangeTest {

	@ParameterizedTest(name = "período {0}: {1} -> {2}")
	@CsvSource({
			// Mes de 30 días: el primer día del mes anterior y el día previo; el último día del período y el siguiente.
			"2026-11, 2026-10-01, true",
			"2026-11, 2026-09-30, false",
			"2026-11, 2026-11-30, true",
			"2026-11, 2026-12-01, false",
			// Dentro del rango, en el mes anterior y en el mismo período.
			"2026-11, 2026-10-31, true",
			"2026-11, 2026-11-01, true",
			"2026-11, 2026-11-15, true",
			// Enero: el mes anterior es diciembre del año anterior.
			"2027-01, 2026-12-01, true",
			"2027-01, 2026-11-30, false",
			"2027-01, 2027-01-31, true",
			"2027-01, 2027-02-01, false",
			// Febrero común: termina el 28.
			"2027-02, 2027-01-01, true",
			"2027-02, 2026-12-31, false",
			"2027-02, 2027-02-28, true",
			"2027-02, 2027-03-01, false",
			// Febrero bisiesto: termina el 29.
			"2028-02, 2028-01-01, true",
			"2028-02, 2027-12-31, false",
			"2028-02, 2028-02-29, true",
			"2028-02, 2028-03-01, false",
			// Marzo: el mes anterior es febrero, común o bisiesto, y vale desde su día 1.
			"2027-03, 2027-02-01, true",
			"2027-03, 2027-01-31, false",
			"2028-03, 2028-02-01, true",
			"2028-03, 2028-01-31, false",
	})
	void containsOnlyDatesBetweenThePreviousMonthStartAndThePeriodEnd(YearMonth period, LocalDate dueDate,
			boolean expected) {
		assertThat(EntryDueDateRange.contains(period, dueDate)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "período {0}: del {1} al {2}")
	@CsvSource({
			"2026-11, 2026-10-01, 2026-11-30",
			"2027-01, 2026-12-01, 2027-01-31",
			"2027-02, 2027-01-01, 2027-02-28",
			"2028-02, 2028-01-01, 2028-02-29",
	})
	void exposesItsLimits(YearMonth period, LocalDate earliest, LocalDate latest) {
		assertThat(EntryDueDateRange.earliest(period)).isEqualTo(earliest);
		assertThat(EntryDueDateRange.latest(period)).isEqualTo(latest);
	}
}
