package com.smartcoin.budgetitem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartcoin.budgetitem.domain.BudgetItemEditEffects.EditedField;
import com.smartcoin.budgetitem.domain.BudgetItemEditEffects.EntryState;
import com.smartcoin.entry.domain.StoredEntryStatus;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RN-15: a qué partidas alcanza cada cambio. Una fila por tipo de partida y por dato editado; las consolidadas y las
 * de períodos cerrados no se tocan nunca (RN-09).
 */
class BudgetItemEditEffectsTest {

	// Columnas: dato editado | período abierto | estado | editada | con movimientos | ¿la alcanza?
	@ParameterizedTest(name = "{0}: abierto={1}, {2}, editada={3}, movimientos={4} → {5}")
	@CsvSource({
			// Estimada (pendiente, sin movimientos, no editada): la alcanza todo.
			"DUE_DATE,       true,  PENDING,      false, false, true",
			"ACCOUNT,        true,  PENDING,      false, false, true",
			"CURRENT_AMOUNT, true,  PENDING,      false, false, true",
			// Parcial (con movimientos): el vencimiento y el monto sí; la cuenta no.
			"DUE_DATE,       true,  PENDING,      false, true,  true",
			"ACCOUNT,        true,  PENDING,      false, true,  false",
			"CURRENT_AMOUNT, true,  PENDING,      false, true,  true",
			// Editada sin movimientos: el vencimiento y la cuenta sí; el monto no.
			"DUE_DATE,       true,  PENDING,      true,  false, true",
			"ACCOUNT,        true,  PENDING,      true,  false, true",
			"CURRENT_AMOUNT, true,  PENDING,      true,  false, false",
			// Editada y parcial: solo el vencimiento.
			"DUE_DATE,       true,  PENDING,      true,  true,  true",
			"ACCOUNT,        true,  PENDING,      true,  true,  false",
			"CURRENT_AMOUNT, true,  PENDING,      true,  true,  false",
			// Consolidada de un período abierto: nada.
			"DUE_DATE,       true,  CONSOLIDATED, false, true,  false",
			"ACCOUNT,        true,  CONSOLIDATED, false, true,  false",
			"CURRENT_AMOUNT, true,  CONSOLIDATED, false, true,  false",
			"CURRENT_AMOUNT, true,  CONSOLIDATED, true,  true,  false",
			// Período cerrado: nada, ni siquiera una pendiente sin movimientos ni editada.
			"DUE_DATE,       false, PENDING,      false, false, false",
			"ACCOUNT,        false, PENDING,      false, false, false",
			"CURRENT_AMOUNT, false, PENDING,      false, false, false",
			// Consolidada de un período cerrado.
			"DUE_DATE,       false, CONSOLIDATED, false, true,  false",
			"ACCOUNT,        false, CONSOLIDATED, false, true,  false",
			"CURRENT_AMOUNT, false, CONSOLIDATED, false, true,  false",
	})
	void eachChangeReachesExactlyTheEntriesOfTheTable(EditedField field, boolean periodOpen, StoredEntryStatus status,
			boolean manual, boolean hasMovements, boolean expected) {
		EntryState entry = new EntryState(periodOpen, status, manual);

		assertThat(BudgetItemEditEffects.affects(field, entry, hasMovements)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "abierto={0}, {1} → pendiente de período abierto: {2}")
	@CsvSource({
			"true,  PENDING,      true",
			"true,  CONSOLIDATED, false",
			"false, PENDING,      false",
			"false, CONSOLIDATED, false",
	})
	void onlyAPendingEntryOfAnOpenPeriodIsEditable(boolean periodOpen, StoredEntryStatus status, boolean expected) {
		assertThat(BudgetItemEditEffects.isOpenPending(new EntryState(periodOpen, status, false))).isEqualTo(expected);
		assertThat(BudgetItemEditEffects.isOpenPending(new EntryState(periodOpen, status, true))).isEqualTo(expected);
	}
}
