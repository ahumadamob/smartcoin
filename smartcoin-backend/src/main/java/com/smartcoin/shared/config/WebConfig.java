package com.smartcoin.shared.config;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(new YearMonthConverter());
	}

	/** Convierte períodos {@code YYYY-MM} de rutas y consultas; cualquier otro formato es inválido. */
	static class YearMonthConverter implements Converter<String, YearMonth> {

		private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM");

		@Override
		public YearMonth convert(String source) {
			try {
				return YearMonth.parse(source.trim(), FORMAT);
			}
			catch (DateTimeParseException e) {
				throw new IllegalArgumentException("Período inválido, se espera el formato YYYY-MM: " + source, e);
			}
		}
	}
}
