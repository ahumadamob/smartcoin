package com.smartcoin.report.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.smartcoin.shared.domain.EntryKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Fecha efectiva y pendiente con signo de una partida pendiente (D-46, RN-20), con hoy el 17/10/2026. */
class PendingEntryRuleTest {

	static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

	@ParameterizedTest
	@CsvSource({
			"2026-10-17, 2026-10-17, false",  // vence hoy: no está vencida, su fecha es hoy
			"2026-10-18, 2026-10-18, false",  // mañana
			"2026-12-31, 2026-12-31, false",  // futuro
			"2026-10-16, 2026-10-17, true",   // ayer: vencida, cuenta hoy
			"2026-08-01, 2026-10-17, true"    // vencida hace meses: también hoy, nunca antes
	})
	void theEffectiveDateIsTheDueDateOrTodayIfItAlreadyPassed(LocalDate dueDate, LocalDate effective, boolean overdue) {
		assertThat(PendingEntryRule.effectiveDate(dueDate, TODAY)).isEqualTo(effective);
		assertThat(PendingEntryRule.isOverdue(dueDate, TODAY)).isEqualTo(overdue);
	}

	@Test
	void anIncomeAddsAndAnExpenseSubtracts() {
		assertThat(PendingEntryRule.signedPending(EntryKind.INCOME, new BigDecimal("300.00")))
				.isEqualByComparingTo("300.00");
		assertThat(PendingEntryRule.signedPending(EntryKind.EXPENSE, new BigDecimal("300.00")))
				.isEqualByComparingTo("-300.00");
	}

	@Test
	void aZeroPendingDoesNotCount() {
		assertThat(PendingEntryRule.counts(BigDecimal.ZERO)).isFalse();
		assertThat(PendingEntryRule.counts(new BigDecimal("0.01"))).isTrue();
	}
}
