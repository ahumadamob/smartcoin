package com.smartcoin.shared.config;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YearMonthConverterTest {

	private final WebConfig.YearMonthConverter converter = new WebConfig.YearMonthConverter();

	@Test
	void parsesPeriod() {
		assertThat(converter.convert("2026-10")).isEqualTo(YearMonth.of(2026, 10));
		assertThat(converter.convert("2026-01")).isEqualTo(YearMonth.of(2026, 1));
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-13", "2026-00", "2026-1", "26-10", "2026/10", "2026-10-01", "octubre", "" })
	void rejectsInvalidPeriod(String value) {
		assertThatThrownBy(() -> converter.convert(value)).isInstanceOf(IllegalArgumentException.class);
	}
}
