package com.smartcoin.budgetitem.domain;

import com.smartcoin.entry.domain.StoredEntryStatus;

/**
 * A qué partidas de un Concepto alcanza cada cambio de su edición (RN-15). Nunca se toca una partida consolidada ni
 * de un período cerrado (RN-09). Regla pura: no sabe de base de datos ni de repositorios.
 */
public final class BudgetItemEditEffects {

	/** Los datos editables que se propagan a las partidas. Nombre, categoría y regla de estimación no las tocan. */
	public enum EditedField {
		/** Día de vencimiento o desfase de mes. */
		DUE_DATE,
		/** Cuenta por defecto. */
		ACCOUNT,
		/** Monto vigente. */
		CURRENT_AMOUNT
	}

	/**
	 * Lo que importa de una partida recurrente para decidir si un cambio la alcanza.
	 *
	 * @param periodOpen {@code true} si su período está abierto
	 * @param manual {@code true} si el usuario editó su monto presupuestado (partida editada)
	 */
	public record EntryState(boolean periodOpen, StoredEntryStatus status, boolean manual) {
	}

	private BudgetItemEditEffects() {
	}

	/** Pendiente de un período abierto: la única que un cambio del Concepto puede tocar. */
	public static boolean isOpenPending(EntryState entry) {
		return entry.periodOpen() && entry.status() == StoredEntryStatus.PENDING;
	}

	/**
	 * ¿El cambio de ese dato alcanza a la partida?
	 * <ul>
	 * <li>Vencimiento: toda pendiente de un período abierto, tenga movimientos o esté editada o no.</li>
	 * <li>Cuenta: la pendiente de un período abierto sin movimientos, editada o no.</li>
	 * <li>Monto vigente: la pendiente de un período abierto no editada, tenga o no movimientos.</li>
	 * </ul>
	 */
	public static boolean affects(EditedField field, EntryState entry, boolean hasMovements) {
		if (!isOpenPending(entry)) {
			return false;
		}
		return switch (field) {
			case DUE_DATE -> true;
			case ACCOUNT -> !hasMovements;
			case CURRENT_AMOUNT -> !entry.manual();
		};
	}
}
