package com.smartcoin.entry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RN-20, con hoy fijo en 2026-10-08. */
class OverdueRuleTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

	@ParameterizedTest(name = "{0}, vence {1} → vencida: {2}")
	@CsvSource({
			"PENDING,      2026-10-07, true",
			"PENDING,      2026-10-08, false",
			"PENDING,      2026-10-09, false",
			// Con desfase −1 el vencimiento puede ser de un mes anterior (S-20).
			"PENDING,      2026-09-25, true",
			"PENDING,      2025-12-31, true",
			"PENDING,      2027-01-01, false",
			// Una consolidada nunca está vencida.
			"CONSOLIDATED, 2026-10-07, false",
			"CONSOLIDATED, 2026-10-08, false",
			"CONSOLIDATED, 2026-10-09, false",
			"CONSOLIDATED, 2020-01-01, false",
	})
	void overdue(StoredEntryStatus stored, LocalDate dueDate, boolean expected) {
		assertThat(OverdueRule.isOverdue(stored, dueDate, TODAY)).isEqualTo(expected);
	}
}
