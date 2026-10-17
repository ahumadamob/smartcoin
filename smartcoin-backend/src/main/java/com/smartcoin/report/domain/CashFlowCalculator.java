package com.smartcoin.report.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

/**
 * Flujo de caja de una moneda para un rango de fechas (RN-45, D-46): saldo de arranque, filas en orden cronológico y
 * saldo acumulado en cada una. Regla pura: recibe los datos ya cargados y no redondea; los montos tienen escala 2.
 * Nunca mezcla monedas (RN-04): todo lo que no es de la moneda pedida se ignora.
 *
 * <p>La fecha efectiva de un pendiente es su vencimiento, o hoy si ya venció (RN-20): nunca es anterior a hoy. Cae en
 * el rango, y es una fila prevista; cae antes del rango (solo si el rango empieza después de hoy), y suma al saldo de
 * arranque, que entonces es proyectado; cae después, y no se lista.
 */
public final class CashFlowCalculator {

	private CashFlowCalculator() {
	}

	/** Si la fila ya ocurrió o está prevista. */
	public enum CashFlowRowStatus {
		REAL,
		PLANNED
	}

	/** De dónde sale la fila. El orden del enum es el desempate final entre filas iguales. */
	public enum CashFlowRowType {
		ACCOUNT_OPENING,
		MOVEMENT,
		TRANSFER,
		PENDING_ENTRY
	}

	/** Un movimiento: {@code amount} es el monto cargado (mayor que 0); el signo lo da el tipo de la partida. */
	public record MovementLine(long id, LocalDate date, EntryKind kind, String entryName, String accountName,
			Currency currency, BigDecimal amount) {
	}

	/** Una transferencia, con la moneda y el monto de cada lado. */
	public record TransferLine(long id, LocalDate date, String sourceAccountName, Currency sourceCurrency,
			BigDecimal sourceAmount, String targetAccountName, Currency targetCurrency, BigDecimal targetAmount) {
	}

	/** Una partida pendiente; {@code pendingAmount} es el pendiente de RN-17. */
	public record PendingLine(long id, EntryKind kind, String entryName, String accountName, Currency currency,
			LocalDate dueDate, BigDecimal pendingAmount) {
	}

	/** Una cuenta con su fecha de apertura y su saldo inicial. */
	public record AccountOpening(long id, String accountName, Currency currency, LocalDate openingDate,
			BigDecimal initialBalance) {
	}

	/**
	 * @param currency       moneda pedida
	 * @param from           primer día del rango
	 * @param to             último día del rango
	 * @param today          hoy, del {@code Clock} (RN-02)
	 * @param openingBalance suma de los saldos reales (RN-35) al día anterior a {@code from} de las cuentas de la moneda
	 *                       <b>abiertas antes de {@code from}</b>
	 * @param accounts       cuentas de la moneda abiertas desde {@code from} (las posteriores a {@code to} se ignoran)
	 */
	public record Input(Currency currency, LocalDate from, LocalDate to, LocalDate today, BigDecimal openingBalance,
			List<AccountOpening> accounts, List<MovementLine> movements, List<TransferLine> transfers,
			List<PendingLine> pending) {
	}

	/** Una fila. {@code dueDate} y {@code overdue} solo tienen sentido en las previstas de partidas pendientes. */
	public record Row(LocalDate date, CashFlowRowStatus status, CashFlowRowType type, String description,
			BigDecimal amount, BigDecimal balance, LocalDate dueDate, boolean overdue) {
	}

	/**
	 * @param openingBalanceProjected si el arranque incluye pendientes (el rango empieza después de hoy)
	 * @param closingBalance          el acumulado de la última fila, o el arranque si no hay filas
	 */
	public record Result(BigDecimal openingBalance, boolean openingBalanceProjected, List<Row> rows,
			BigDecimal closingBalance) {
	}

	public static Result calculate(Input input) {
		Objects.requireNonNull(input, "input");
		Currency currency = input.currency();
		LocalDate from = input.from();
		LocalDate to = input.to();

		BigDecimal opening = input.openingBalance();
		List<Entry> entries = new ArrayList<>();

		for (AccountOpening account : input.accounts()) {
			if (account.currency() == currency && inRange(account.openingDate(), from, to)
					&& account.initialBalance().signum() != 0) {
				entries.add(new Entry(account.openingDate(), Group.OPENING, CashFlowRowType.ACCOUNT_OPENING,
						account.id(), "Saldo inicial de " + account.accountName(), account.initialBalance(), null,
						false));
			}
		}
		for (MovementLine movement : input.movements()) {
			if (movement.currency() == currency && inRange(movement.date(), from, to)) {
				BigDecimal signed = movement.kind() == EntryKind.INCOME ? movement.amount() : movement.amount().negate();
				entries.add(new Entry(movement.date(), Group.REAL, CashFlowRowType.MOVEMENT, movement.id(),
						movement.entryName() + " · " + movement.accountName(), signed, null, false));
			}
		}
		for (TransferLine transfer : input.transfers()) {
			if (inRange(transfer.date(), from, to)) {
				BigDecimal signed = transferAmount(transfer, currency);
				if (signed != null) {
					entries.add(new Entry(transfer.date(), Group.REAL, CashFlowRowType.TRANSFER, transfer.id(),
							transferDescription(transfer), signed, null, false));
				}
			}
		}
		for (PendingLine line : input.pending()) {
			if (line.currency() != currency || !PendingEntryRule.counts(line.pendingAmount())) {
				continue;
			}
			LocalDate effective = PendingEntryRule.effectiveDate(line.dueDate(), input.today());
			BigDecimal signed = PendingEntryRule.signedPending(line.kind(), line.pendingAmount());
			if (effective.isBefore(from)) {
				opening = opening.add(signed);
			}
			else if (!effective.isAfter(to)) {
				entries.add(new Entry(effective, Group.PLANNED, CashFlowRowType.PENDING_ENTRY, line.id(),
						line.entryName() + " · " + line.accountName(), signed, line.dueDate(),
						PendingEntryRule.isOverdue(line.dueDate(), input.today())));
			}
		}

		entries.sort(ORDER);
		BigDecimal balance = opening;
		List<Row> rows = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			balance = balance.add(entry.amount());
			CashFlowRowStatus status = entry.group() == Group.PLANNED ? CashFlowRowStatus.PLANNED
					: CashFlowRowStatus.REAL;
			rows.add(new Row(entry.date(), status,
					entry.type(), entry.description(), entry.amount().setScale(2), balance.setScale(2),
					entry.dueDate(), entry.overdue()));
		}
		return new Result(opening.setScale(2), from.isAfter(input.today()), List.copyOf(rows), balance.setScale(2));
	}

	/**
	 * El monto con el signo de la moneda pedida, o {@code null} si la transferencia no la cambia: resta el monto de
	 * origen si el origen es de esa moneda y suma el de destino si lo es el destino. Entre cuentas de la misma moneda
	 * no cambia el total (RN-45) y no se lista.
	 */
	private static BigDecimal transferAmount(TransferLine transfer, Currency currency) {
		if (transfer.sourceCurrency() == transfer.targetCurrency()) {
			return null;
		}
		if (transfer.sourceCurrency() == currency) {
			return transfer.sourceAmount().negate();
		}
		if (transfer.targetCurrency() == currency) {
			return transfer.targetAmount();
		}
		return null;
	}

	/** Compra de dólares (de ARS a USD) o venta de dólares (de USD a ARS), con las dos cuentas (glosario). */
	private static String transferDescription(TransferLine transfer) {
		String kind = transfer.sourceCurrency() == Currency.ARS ? "Compra de dólares" : "Venta de dólares";
		return kind + ": " + transfer.sourceAccountName() + " → " + transfer.targetAccountName();
	}

	private static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
		return !date.isBefore(from) && !date.isAfter(to);
	}

	/** Dentro de un día: saldo inicial de cuenta, real y prevista. */
	private enum Group {
		OPENING,
		REAL,
		PLANNED
	}

	private record Entry(LocalDate date, Group group, CashFlowRowType type, long id, String description,
			BigDecimal amount, LocalDate dueDate, boolean overdue) {
	}

	/**
	 * Fecha; grupo; en cada grupo, los montos positivos antes que los negativos (ingresos antes que gastos);
	 * descripción sin distinguir mayúsculas; y orden de creación (origen e id).
	 */
	private static final Comparator<Entry> ORDER = Comparator
			.comparing(Entry::date)
			.thenComparing(Entry::group)
			.thenComparing((Entry e) -> e.amount().signum() < 0)
			.thenComparing(Entry::description, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Entry::type)
			.thenComparingLong(Entry::id);
}
