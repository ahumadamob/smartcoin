package com.smartcoin.account.domain;

import java.time.LocalDate;

/**
 * Qué datos del usuario dependen de una cuenta (RN-33).
 *
 * @param referenced        la cuenta es la de algún Concepto, partida, movimiento, transferencia o cierre
 * @param hasClosings       la cuenta tiene al menos un cierre de cuenta
 * @param firstActivityDate fecha de su primer movimiento o transferencia, o {@code null} si no tiene ninguno
 */
public record AccountUsage(boolean referenced, boolean hasClosings, LocalDate firstActivityDate) {

	/** Cuenta sin ninguna referencia. */
	public static AccountUsage unused() {
		return new AccountUsage(false, false, null);
	}
}
