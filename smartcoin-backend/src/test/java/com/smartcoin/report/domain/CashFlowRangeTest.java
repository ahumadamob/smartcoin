package com.smartcoin.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.stream.Stream;

import com.smartcoin.report.domain.CashFlowRange.Range;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** D-46: extremos omitidos y límites del rango (período inicial, horizonte y 366 días). Hoy es el 17/10/2026. */
class CashFlowRangeTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
	private static final YearMonth START = YearMonth.of(2026, 8);
	private static final YearMonth HORIZON = YearMonth.of(2028, 10);

	private static LocalDate date(String text) {
		return text == null ? null : LocalDate.parse(text);
	}

	private static Range resolve(String from, String to) {
		return CashFlowRange.resolve(date(from), date(to), TODAY, START, HORIZON);
	}

	@Test
	void withoutFromAndToItIsTheCurrentMonth() {
		assertThat(resolve(null, null)).isEqualTo(new Range(date("2026-10-01"), date("2026-10-31")));
	}

	@ParameterizedTest(name = "from={0} to={1} → {2} a {3}")
	@MethodSource("completedRanges")
	void missingEndsAreCompletedWithTheMonthOfTheOtherOne(String from, String to, String expectedFrom,
			String expectedTo) {
		assertThat(resolve(from, to)).isEqualTo(new Range(date(expectedFrom), date(expectedTo)));
	}

	static Stream<Arguments> completedRanges() {
		return Stream.of(
				Arguments.of("2026-11-10", null, "2026-11-10", "2026-11-30"),
				Arguments.of("2028-02-01", null, "2028-02-01", "2028-02-29"),
				Arguments.of(null, "2026-11-10", "2026-11-01", "2026-11-10"),
				Arguments.of("2026-10-05", "2026-10-05", "2026-10-05", "2026-10-05"),
				Arguments.of("2026-08-01", "2026-08-31", "2026-08-01", "2026-08-31"));
	}

	@ParameterizedTest(name = "{0} a {1}: error en {2}")
	@MethodSource("invalidRanges")
	void anInvalidRangeIsAValidationErrorOnTheFieldThatCausesIt(String from, String to, String field) {
		assertThatThrownBy(() -> resolve(from, to)).isInstanceOfSatisfying(BusinessException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(e.field()).isEqualTo(field);
		});
	}

	static Stream<Arguments> invalidRanges() {
		return Stream.of(
				// Invertido.
				Arguments.of("2026-10-10", "2026-10-09", "from"),
				// Un día antes del primer día del período inicial.
				Arguments.of("2026-07-31", "2026-08-31", "from"),
				// Un día después del último día del horizonte.
				Arguments.of("2028-10-01", "2028-11-01", "to"),
				// 367 días.
				Arguments.of("2026-10-01", "2027-10-02", "to"),
				// Sin to: el mes de from está antes del período inicial.
				Arguments.of("2026-07-15", null, "from"),
				// Sin from: el mes de to pasa el horizonte.
				Arguments.of(null, "2028-11-02", "to"));
	}

	@Test
	void theLimitsThemselvesAreValid() {
		assertThat(resolve("2026-08-01", "2026-12-31")).isEqualTo(new Range(date("2026-08-01"), date("2026-12-31")));
		assertThat(resolve("2028-10-31", "2028-10-31")).isEqualTo(new Range(date("2028-10-31"), date("2028-10-31")));
	}

	@Test
	void aRangeOf366DaysIsValidAndOf367IsNot() {
		// 2026-10-01 a 2027-10-01 incluye ambos extremos: 366 días.
		assertThat(resolve("2026-10-01", "2027-10-01").to()).isEqualTo(date("2027-10-01"));
		assertThatThrownBy(() -> resolve("2026-10-01", "2027-10-02")).isInstanceOf(BusinessException.class);
	}

	@Test
	void aLeapYearRangeOf366DaysIsValid() {
		assertThat(resolve("2027-03-01", "2028-02-29").to()).isEqualTo(date("2028-02-29"));
		assertThat(CashFlowRange.MAX_DAYS).isEqualTo(366);
	}
}
