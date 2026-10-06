package com.smartcoin.account.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RN-33: la fecha de apertura va del primer día del período inicial a hoy, ambos inclusive. */
class OpeningDateRuleTest {

	// Pasadas las 21:00 del 5/10 en Mendoza ya es 6/10 en UTC: "hoy" sale de la zona del negocio.
	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T14:00:00Z"),
			ZoneId.of("America/Argentina/Mendoza"));
	static final LocalDate TODAY = LocalDate.now(CLOCK);
	static final YearMonth START = YearMonth.of(2026, 8);

	@Test
	void todayComesFromTheBusinessZoneClock() {
		assertThat(TODAY).isEqualTo(LocalDate.of(2026, 10, 6));
	}

	@Test
	void suggestsTheFirstDayOfTheStartPeriod() {
		assertThat(OpeningDateRule.suggested(START)).isEqualTo(LocalDate.of(2026, 8, 1));
	}

	@Test
	void acceptsTheFirstDayOfTheStartPeriod() {
		assertThatCode(() -> OpeningDateRule.validate(LocalDate.of(2026, 8, 1), START, TODAY))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsTheDayBeforeTheStartPeriod() {
		assertThatThrownBy(() -> OpeningDateRule.validate(LocalDate.of(2026, 7, 31), START, TODAY))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.getMessage()).contains("01/08/2026");
				});
	}

	@Test
	void acceptsTodayAndAnyDateInBetween() {
		assertThatCode(() -> OpeningDateRule.validate(TODAY, START, TODAY)).doesNotThrowAnyException();
		assertThatCode(() -> OpeningDateRule.validate(LocalDate.of(2026, 9, 15), START, TODAY))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsTomorrow() {
		assertThatThrownBy(() -> OpeningDateRule.validate(TODAY.plusDays(1), START, TODAY))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.getMessage()).contains("futura");
				});
	}

	@Test
	void whenTheStartPeriodIsTheCurrentMonthTheRangeIsFirstDayToToday() {
		YearMonth current = YearMonth.from(TODAY);
		assertThatCode(() -> OpeningDateRule.validate(LocalDate.of(2026, 10, 1), current, TODAY))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> OpeningDateRule.validate(LocalDate.of(2026, 9, 30), current, TODAY))
				.isInstanceOf(BusinessException.class);
	}
}
