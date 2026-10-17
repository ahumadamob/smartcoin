package com.smartcoin.report.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

/**
 * Proyección de saldos por cuenta (RN-46, HU-35). Para cada mes desde el actual hasta {@code until} y cada cuenta, el
 * saldo proyectado al último día del mes es el saldo actual más el pendiente de las partidas de ingreso pendientes de
 * esa cuenta con fecha efectiva hasta ese día, menos el de las de gasto. La fecha efectiva es el vencimiento, o hoy si
 * ya venció ({@link PendingEntryRule}), así que un pendiente vencido cuenta desde el mes actual.
 *
 * <p>Regla pura: recibe los datos ya cargados y no redondea; los montos tienen escala 2. Supone que cada pendiente se
 * cobra o paga en la cuenta prevista de su partida. Los subtotales son por moneda: nunca suma ARS con USD (RN-04).
 */
public final class ProjectionCalculator {

	private ProjectionCalculator() {
	}

	/** Una cuenta con su saldo actual (RN-35, a hoy). El orden de la lista es el de las columnas. */
	public record AccountStart(long id, String name, Currency currency, BigDecimal currentBalance) {
	}

	/** Una partida pendiente; {@code pendingAmount} es el pendiente de RN-17 y la cuenta es la prevista. */
	public record PendingLine(long accountId, EntryKind kind, LocalDate dueDate, BigDecimal pendingAmount) {
	}

	/**
	 * @param today    hoy, del {@code Clock} (RN-02)
	 * @param until    último mes a proyectar, no anterior al mes de hoy
	 * @param accounts cuentas del usuario, en el orden en que se informan
	 * @param pending  partidas pendientes del usuario
	 */
	public record Input(LocalDate today, YearMonth until, List<AccountStart> accounts, List<PendingLine> pending) {
	}

	public record AccountBalance(long accountId, BigDecimal balance) {
	}

	public record CurrencySubtotal(Currency currency, BigDecimal balance) {
	}

	/** Un mes: el saldo de cada cuenta (en el orden de la entrada) y los subtotales de las monedas con cuentas. */
	public record Month(YearMonth month, List<AccountBalance> balances, List<CurrencySubtotal> subtotals) {
	}

	public record Result(YearMonth from, YearMonth until, List<AccountStart> accounts, List<Month> months) {
	}

	public static Result calculate(Input input) {
		Objects.requireNonNull(input, "input");
		YearMonth current = YearMonth.from(input.today());
		if (input.until().isBefore(current)) {
			throw new IllegalArgumentException("until no puede ser anterior al mes actual");
		}
		int monthCount = (int) current.until(input.until(), ChronoUnit.MONTHS) + 1;
		List<AccountStart> accounts = List.copyOf(input.accounts());

		// Lo que cada cuenta suma o resta en cada mes (por fecha efectiva); después se acumula.
		Map<Long, BigDecimal[]> deltas = new HashMap<>();
		for (AccountStart account : accounts) {
			BigDecimal[] zeros = new BigDecimal[monthCount];
			Arrays.fill(zeros, BigDecimal.ZERO);
			deltas.put(account.id(), zeros);
		}
		for (PendingLine line : input.pending()) {
			BigDecimal[] byMonth = deltas.get(line.accountId());
			if (byMonth == null || !PendingEntryRule.counts(line.pendingAmount())) {
				continue;
			}
			YearMonth effective = YearMonth.from(PendingEntryRule.effectiveDate(line.dueDate(), input.today()));
			if (effective.isAfter(input.until())) {
				continue;
			}
			int index = (int) current.until(effective, ChronoUnit.MONTHS);
			byMonth[index] = byMonth[index].add(PendingEntryRule.signedPending(line.kind(), line.pendingAmount()));
		}

		BigDecimal[] running = new BigDecimal[accounts.size()];
		for (int i = 0; i < running.length; i++) {
			running[i] = accounts.get(i).currentBalance();
		}
		List<Month> months = new ArrayList<>(monthCount);
		for (int m = 0; m < monthCount; m++) {
			List<AccountBalance> balances = new ArrayList<>(accounts.size());
			Map<Currency, BigDecimal> subtotals = new EnumMap<>(Currency.class);
			for (int i = 0; i < running.length; i++) {
				AccountStart account = accounts.get(i);
				running[i] = running[i].add(deltas.get(account.id())[m]);
				BigDecimal balance = running[i].setScale(2);
				balances.add(new AccountBalance(account.id(), balance));
				subtotals.merge(account.currency(), balance, BigDecimal::add);
			}
			List<CurrencySubtotal> subtotalList = subtotals.entrySet().stream()
					.map(e -> new CurrencySubtotal(e.getKey(), e.getValue().setScale(2)))
					.toList();
			months.add(new Month(current.plusMonths(m), List.copyOf(balances), subtotalList));
		}
		return new Result(current, input.until(), accounts, List.copyOf(months));
	}
}
