package com.smartcoin.entry.domain;

import java.time.LocalDate;

/**
 * Partida vencida (RN-20): pendiente y con vencimiento anterior a hoy. «Pendiente» es el estado guardado, no el monto:
 * una partida pagada por completo pero sin consolidar sigue vencida hasta que se consolida. Es solo un indicador.
 */
public final class OverdueRule {

	private OverdueRule() {
	}

	/** @param today hoy, del {@code Clock} (RN-02) */
	public static boolean isOverdue(StoredEntryStatus stored, LocalDate dueDate, LocalDate today) {
		return stored == StoredEntryStatus.PENDING && dueDate.isBefore(today);
	}
}
