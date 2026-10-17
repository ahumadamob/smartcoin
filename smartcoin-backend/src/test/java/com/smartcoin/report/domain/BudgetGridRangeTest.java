package com.smartcoin.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;

import com.smartcoin.report.domain.BudgetGridRange.Range;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** HU-36, D-48: el rango de la vista de varios meses. El usuario tiene 2026-08 a 2028-10 y estamos en 2026-10. */
class BudgetGridRangeTest {

	static final YearMonth CURRENT = YearMonth.of(2026, 10);
	static final YearMonth START = YearMonth.of(2026, 8);
	static final YearMonth HORIZON = YearMonth.of(2028, 10);

	static Range resolve(String from, String to) {
		return BudgetGridRange.resolve(from == null ? null : YearMonth.parse(from),
				to == null ? null : YearMonth.parse(to), CURRENT, START, HORIZON);
	}

	@Test
	void withoutParametersItIsTwelveMonthsFromTheCurrentOne() {
		assertThat(resolve(null, null)).isEqualTo(new Range(YearMonth.of(2026, 10), YearMonth.of(2027, 9)));
	}

	@Test
	void withoutToItIsTwelveMonthsFromFrom() {
		assertThat(resolve("2027-01", null)).isEqualTo(new Range(YearMonth.of(2027, 1), YearMonth.of(2027, 12)));
	}

	@Test
	void withoutToItDoesNotGoPastTheHorizon() {
		assertThat(resolve("2028-06", null)).isEqualTo(new Range(YearMonth.of(2028, 6), HORIZON));
	}

	@Test
	void withoutFromItIsTwelveMonthsUpToTo() {
		assertThat(resolve(null, "2027-12")).isEqualTo(new Range(YearMonth.of(2027, 1), YearMonth.of(2027, 12)));
	}

	@Test
	void withoutFromItDoesNotGoBeforeTheStartPeriod() {
		assertThat(resolve(null, "2026-10")).isEqualTo(new Range(START, YearMonth.of(2026, 10)));
	}

	@Test
	void aSingleMonthAndTheMaximumLengthAreValid() {
		assertThat(resolve("2026-10", "2026-10")).isEqualTo(new Range(CURRENT, CURRENT));
		assertThat(resolve("2026-10", "2028-09").to()).isEqualTo(YearMonth.of(2028, 9));
	}

	@Test
	void theWholeStoredRangeOf27MonthsIsTooLong() {
		assertThatThrownBy(() -> resolve("2026-08", "2028-10")).isInstanceOf(BusinessException.class)
				.satisfies(e -> {
					BusinessException b = (BusinessException) e;
					assertThat(b.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(b.field()).isEqualTo("to");
				});
	}

	@Test
	void twentyFiveMonthsIsTooLongAndTwentyFourIsTheLimit() {
		assertThat(resolve("2026-10", "2028-09")).isNotNull();
		assertThatThrownBy(() -> resolve("2026-09", "2028-09")).isInstanceOf(BusinessException.class);
	}

	@ParameterizedTest
	@CsvSource({ "2027-03,2027-01,from", "2026-07,2026-12,from", "2026-10,2028-11,to", "2029-01,2029-02,to" })
	void invalidRangesAreValidationErrorsOnTheOffendingField(String from, String to, String field) {
		assertThatThrownBy(() -> resolve(from, to)).isInstanceOf(BusinessException.class).satisfies(e -> {
			BusinessException b = (BusinessException) e;
			assertThat(b.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(b.field()).isEqualTo(field);
		});
	}
}
