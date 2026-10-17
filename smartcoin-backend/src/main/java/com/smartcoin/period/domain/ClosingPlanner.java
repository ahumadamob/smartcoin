package com.smartcoin.period.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

/**
 * Plan de cierre de un período (RN-38 a RN-40). Regla pura: recibe valores y devuelve resultados.
 *
 * <p>HU-30 arma el diagnóstico: las precondiciones de RN-38, las partidas pendientes y las cuentas a conciliar
 * (RN-39). Las resoluciones de las pendientes, los saldos reales con sus diferencias y las partidas que crearía el
 * cierre se suman al {@link Input} y al {@link Plan} sin cambiar lo que ya existe.
 */
public final class ClosingPlanner {

	private ClosingPlanner() {
	}

	/**
	 * Una partida del período con sus valores derivados (RN-16, RN-17), tal como en la vista del mes.
	 *
	 * @param name nombre que se muestra: el del Concepto en una recurrente
	 */
	public record EntryLine(Long id, String name, EntryKind kind, Long accountId, String accountName,
			Currency currency, LocalDate dueDate, BigDecimal budgetedAmount, EntryAmounts amounts) {

		public EntryLine {
			Objects.requireNonNull(amounts, "amounts");
		}

		boolean pending() {
			return amounts.status() != EntryStatus.CONSOLIDATED;
		}
	}

	/**
	 * Una cuenta del usuario con su saldo calculado al último día del período (RN-35).
	 *
	 * @param computedBalance saldo con los movimientos y transferencias con fecha menor o igual al último día, sin
	 *                        importar el período de su partida (RN-41)
	 */
	public record AccountLine(Long id, String name, Currency currency, LocalDate openingDate,
			BigDecimal computedBalance) {
	}

	/**
	 * @param period         el período a cerrar
	 * @param status         su estado
	 * @param previousStatus el estado del período anterior; {@code null} si {@code period} es el período inicial
	 * @param startPeriod    si {@code period} es el período inicial del usuario
	 * @param today          hoy, del {@code Clock} (RN-02)
	 * @param entries        todas las partidas del período, en el orden de la vista del mes
	 * @param accounts       todas las cuentas del usuario, en el orden en que se muestran
	 */
	public record Input(YearMonth period, PeriodStatus status, PeriodStatus previousStatus, boolean startPeriod,
			LocalDate today, List<EntryLine> entries, List<AccountLine> accounts) {

		public Input {
			Objects.requireNonNull(period, "period");
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(today, "today");
			if (!startPeriod && previousStatus == null) {
				throw new IllegalArgumentException("Un período que no es el inicial siempre tiene anterior.");
			}
			entries = List.copyOf(entries);
			accounts = List.copyOf(accounts);
		}
	}

	/**
	 * @param lastDay        último día del período: el saldo se calcula a esa fecha y desde ese día se puede cerrar
	 * @param blockers       todas las precondiciones de RN-38 que no se cumplen, en el orden de RN-38
	 * @param pendingEntries las partidas pendientes (Estimadas y Parciales), cada una necesitará una resolución
	 * @param accounts       las cuentas a conciliar: abiertas el último día del período o antes
	 */
	public record Plan(YearMonth period, LocalDate lastDay, List<ClosingBlockReason> blockers,
			List<EntryLine> pendingEntries, List<AccountLine> accounts) {

		/** Si se cumplen todas las precondiciones de RN-38. */
		public boolean preconditionsMet() {
			return blockers.isEmpty();
		}
	}

	/**
	 * Evalúa las tres precondiciones por separado e informa todas las que fallan. Un período cerrado no tiene nada que
	 * resolver ni conciliar: sus listas quedan vacías.
	 */
	public static Plan plan(Input input) {
		LocalDate lastDay = input.period().atEndOfMonth();
		boolean closed = input.status() == PeriodStatus.CLOSED;

		List<ClosingBlockReason> blockers = new ArrayList<>();
		if (closed) {
			blockers.add(ClosingBlockReason.PERIOD_CLOSED);
		}
		if (!input.startPeriod() && input.previousStatus() != PeriodStatus.CLOSED) {
			blockers.add(ClosingBlockReason.PREVIOUS_PERIOD_OPEN);
		}
		if (input.today().isBefore(lastDay)) {
			blockers.add(ClosingBlockReason.PERIOD_NOT_FINISHED);
		}
		if (closed) {
			return new Plan(input.period(), lastDay, List.copyOf(blockers), List.of(), List.of());
		}

		List<EntryLine> pending = input.entries().stream().filter(EntryLine::pending).toList();
		List<AccountLine> accounts = input.accounts().stream()
				.filter(account -> !account.openingDate().isAfter(lastDay))
				.toList();
		return new Plan(input.period(), lastDay, List.copyOf(blockers), pending, accounts);
	}
}
