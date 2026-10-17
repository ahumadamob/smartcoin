package com.smartcoin.report.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.smartcoin.shared.domain.EntryKind;

/**
 * Cómo cuenta una partida pendiente en las vistas que miran hacia adelante (D-46): el flujo de caja (RN-45) y la
 * proyección de saldos (RN-46). Regla pura, sin Spring ni JPA.
 *
 * <p>La fecha efectiva es el vencimiento, o hoy si ya venció (RN-20): nunca es anterior a hoy. El pendiente con signo
 * suma si la partida es de ingreso y resta si es de gasto.
 */
public final class PendingEntryRule {

	private PendingEntryRule() {
	}

	/** El vencimiento, o {@code today} si el vencimiento es anterior a hoy. */
	public static LocalDate effectiveDate(LocalDate dueDate, LocalDate today) {
		return dueDate.isBefore(today) ? today : dueDate;
	}

	/** Si el vencimiento es anterior a hoy (RN-20). */
	public static boolean isOverdue(LocalDate dueDate, LocalDate today) {
		return dueDate.isBefore(today);
	}

	/** El pendiente (RN-17) con el signo del tipo de la partida: positivo en un ingreso, negativo en un gasto. */
	public static BigDecimal signedPending(EntryKind kind, BigDecimal pendingAmount) {
		return kind == EntryKind.INCOME ? pendingAmount : pendingAmount.negate();
	}

	/** Si la partida aporta algo: un pendiente en cero no se cuenta (una pagada por completo y sin consolidar). */
	public static boolean counts(BigDecimal pendingAmount) {
		return pendingAmount.signum() > 0;
	}
}
