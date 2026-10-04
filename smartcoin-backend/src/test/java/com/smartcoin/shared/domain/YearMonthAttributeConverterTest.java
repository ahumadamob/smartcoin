package com.smartcoin.shared.domain;

import java.time.DateTimeException;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YearMonthAttributeConverterTest {

	private final YearMonthAttributeConverter converter = new YearMonthAttributeConverter();

	@Test
	void writesPeriodAsSevenCharacters() {
		assertThat(converter.convertToDatabaseColumn(YearMonth.of(2026, 10))).isEqualTo("2026-10");
		assertThat(converter.convertToDatabaseColumn(YearMonth.of(2026, 1))).isEqualTo("2026-01");
		assertThat(converter.convertToDatabaseColumn(YearMonth.of(2028, 12))).hasSize(7);
	}

	@Test
	void readsPeriod() {
		assertThat(converter.convertToEntityAttribute("2026-10")).isEqualTo(YearMonth.of(2026, 10));
		assertThat(converter.convertToEntityAttribute("2026-01")).isEqualTo(YearMonth.of(2026, 1));
	}

	@Test
	void keepsNulls() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	@Test
	void roundTripsAcrossYearBoundary() {
		YearMonth december = YearMonth.of(2026, 12);
		assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(december)))
			.isEqualTo(december);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-13", "2026-1", "26-10", "octubre", "" })
	void rejectsInvalidStoredValue(String value) {
		assertThatThrownBy(() -> converter.convertToEntityAttribute(value)).isInstanceOf(DateTimeException.class);
	}
}
