package com.smartcoin.account.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

/**
 * Rango de la fecha de apertura de una cuenta (RN-33): entre el primer día del período inicial del usuario y hoy,
 * ambos inclusive.
 */
public final class OpeningDateRule {

	private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private OpeningDateRule() {
	}

	/** Fecha sugerida para una cuenta nueva: el primer día del período inicial. */
	public static LocalDate suggested(YearMonth startPeriod) {
		return startPeriod.atDay(1);
	}

	/** @throws BusinessException {@code VALIDATION_ERROR} si la fecha es anterior al período inicial o futura. */
	public static void validate(LocalDate openingDate, YearMonth startPeriod, LocalDate today) {
		LocalDate first = suggested(startPeriod);
		if (openingDate.isBefore(first)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR,
					"La fecha de apertura no puede ser anterior al " + DISPLAY.format(first)
							+ ", el primer día de tu período inicial.");
		}
		if (openingDate.isAfter(today)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR,
					"La fecha de apertura no puede ser futura.");
		}
	}
}
