package com.smartcoin.shared.domain;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Guarda los períodos como texto {@code YYYY-MM} (columnas {@code CHAR(7)}). */
@Converter(autoApply = true)
public class YearMonthAttributeConverter implements AttributeConverter<YearMonth, String> {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM");

	@Override
	public String convertToDatabaseColumn(YearMonth attribute) {
		return attribute == null ? null : FORMAT.format(attribute);
	}

	@Override
	public YearMonth convertToEntityAttribute(String dbData) {
		return dbData == null ? null : YearMonth.parse(dbData, FORMAT);
	}
}
