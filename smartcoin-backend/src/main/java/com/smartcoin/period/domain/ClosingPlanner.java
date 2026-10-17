package com.smartcoin.period.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

/**
 * Plan de cierre de un período (RN-38 a RN-40). Regla pura: recibe valores y devuelve resultados.
 *
 * <p>HU-30 arma el diagnóstico: las precondiciones de RN-38, las partidas pendientes y las cuentas a conciliar
 * (RN-39). HU-31 suma las resoluciones de las pendientes (RN-40, pasos 1 y 2): con qué monto se consolidaría cada una
 * y las partidas de saldo postergado que se crearían. HU-32 suma la conciliación (RN-40, paso 3): con los saldos reales,
 * la diferencia de cada cuenta y la partida de diferencia de cierre que se crearía. Los cierres de cuenta guardados y
 * el cierre efectivo son de HU-33.
 */
public final class ClosingPlanner {

	/** Largo máximo del nombre de una partida ({@code budget_item.name}, {@code budget_entry.name}). */
	static final int NAME_MAX_LENGTH = 100;

	static final String CARRIED_OVER_PREFIX = "Saldo pendiente: ";

	static final String DIFFERENCE_PREFIX = "Diferencia de cierre: ";

	private static final String ELLIPSIS = "…";

	/**
	 * Meses abreviados del nombre de la partida de diferencia (D-44): tres letras, en minúscula y sin punto. Es una
	 * tabla fija y no el locale de Java, cuyas abreviaturas cambian entre versiones.
	 */
	private static final List<String> MONTH_ABBREVIATIONS = List.of("ene", "feb", "mar", "abr", "may", "jun", "jul",
			"ago", "sep", "oct", "nov", "dic");

	private ClosingPlanner() {
	}

	/**
	 * Una partida del período con sus valores derivados (RN-16, RN-17), tal como en la vista del mes.
	 *
	 * @param name       nombre que se muestra: el del Concepto en una recurrente
	 * @param categoryId categoría que se muestra: la del Concepto en una recurrente; {@code null} si no tiene
	 */
	public record EntryLine(Long id, EntryOrigin origin, String name, EntryKind kind, Long categoryId,
			String categoryName, Long accountId, String accountName, Currency currency, LocalDate dueDate,
			BigDecimal budgetedAmount, EntryAmounts amounts) {

		public EntryLine {
			Objects.requireNonNull(origin, "origin");
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
	 * @param resolutions    las resoluciones recibidas, por id de partida, en el orden en que llegaron; puede incluir
	 *                       ids que no son pendientes del período (se informan en {@link Plan#invalidResolutions})
	 * @param realBalances   los saldos reales informados, por id de cuenta, en el orden en que llegaron, con hasta 2
	 *                       decimales y que pueden ser negativos (RN-03); puede incluir ids de cuentas que no se
	 *                       concilian (se informan en {@link Plan#invalidBalances})
	 */
	public record Input(YearMonth period, PeriodStatus status, PeriodStatus previousStatus, boolean startPeriod,
			LocalDate today, List<EntryLine> entries, List<AccountLine> accounts,
			Map<Long, ClosingResolution> resolutions, Map<Long, BigDecimal> realBalances) {

		public Input {
			Objects.requireNonNull(period, "period");
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(today, "today");
			if (!startPeriod && previousStatus == null) {
				throw new IllegalArgumentException("Un período que no es el inicial siempre tiene anterior.");
			}
			entries = List.copyOf(entries);
			accounts = List.copyOf(accounts);
			resolutions = Collections.unmodifiableMap(new LinkedHashMap<>(resolutions));
			realBalances = Collections.unmodifiableMap(new LinkedHashMap<>(realBalances));
			realBalances.values().forEach(Input::requireMoney);
		}

		private static void requireMoney(BigDecimal amount) {
			if (amount.stripTrailingZeros().scale() > 2) {
				throw new IllegalArgumentException("Un saldo real tiene hasta 2 decimales: " + amount);
			}
		}
	}

	/**
	 * Una partida que crearía el cierre en el período siguiente. HU-31: las de saldo postergado (RN-40, paso 1). HU-32:
	 * las de diferencia de cierre (paso 3), sin categoría (D-44).
	 *
	 * @param sourceEntryId   en un saldo postergado, la partida cuyo saldo se posterga; {@code null} en los demás
	 * @param sourceAccountId en una diferencia de cierre, la cuenta conciliada: como {@code account_closing} es único
	 *                        por período y cuenta, el cierre efectivo (HU-33) guarda primero los cierres de cuenta,
	 *                        busca cada uno por (período, cuenta) y completa {@code source_closing_id}; {@code null}
	 *                        en los demás
	 */
	public record NewEntry(YearMonth period, EntryOrigin origin, String name, EntryKind kind, Long categoryId,
			String categoryName, Long accountId, String accountName, Currency currency, LocalDate dueDate,
			BigDecimal budgetedAmount, Long sourceEntryId, Long sourceAccountId) {
	}

	/**
	 * Lo que pasaría con una partida pendiente resuelta (RN-40, pasos 1 y 2). Ninguna de las dos resoluciones propaga
	 * ni cambia el Concepto (RN-26).
	 *
	 * @param consolidatedAmount el monto con que quedaría consolidada: su real, 0 si no tuvo movimientos
	 * @param carriedOver        la partida de saldo postergado que se crearía; {@code null} con Cerrar con lo
	 *                           registrado o con pendiente 0
	 */
	public record EntryEffect(Long entryId, ClosingResolution resolution, BigDecimal consolidatedAmount,
			NewEntry carriedOver) {
	}

	/** Una resolución recibida que no corresponde a una partida pendiente del período (RN-39). */
	public record InvalidResolution(Long entryId, InvalidResolutionReason reason) {
	}

	/**
	 * La conciliación de una cuenta con saldo real informado (RN-40, paso 3).
	 *
	 * @param computedBalance saldo calculado al último día del período (RN-35, RN-41)
	 * @param realBalance     saldo informado por el usuario
	 * @param difference      real menos calculado; negativa si el banco tiene menos de lo que la aplicación espera
	 * @param differenceEntry la partida de diferencia de cierre que se crearía; {@code null} si la diferencia es 0
	 */
	public record Reconciliation(Long accountId, BigDecimal computedBalance, BigDecimal realBalance,
			BigDecimal difference, NewEntry differenceEntry) {
	}

	/** Un saldo real recibido para una cuenta que no se concilia en el período (RN-39). */
	public record InvalidBalance(Long accountId, InvalidBalanceReason reason) {
	}

	/**
	 * @param lastDay        último día del período: el saldo se calcula a esa fecha y desde ese día se puede cerrar
	 * @param blockers       todas las precondiciones de RN-38 que no se cumplen, en el orden de RN-38
	 * @param pendingEntries las partidas pendientes (Estimadas y Parciales), cada una necesitará una resolución
	 * @param accounts       las cuentas a conciliar: abiertas el último día del período o antes
	 * @param effects        una por cada pendiente con resolución, en el orden de {@code pendingEntries}
	 * @param newEntries     las partidas que crearía el cierre en el período siguiente: primero las de saldo
	 *                       postergado, en el orden de {@code pendingEntries}, y después las de diferencia de cierre,
	 *                       en el orden de {@code accounts}
	 * @param unresolvedEntryIds las pendientes que todavía no tienen resolución, en el mismo orden
	 * @param invalidResolutions las resoluciones recibidas que no corresponden, en el orden en que llegaron: no se
	 *                           aplican
	 * @param reconciliations    una por cada cuenta de {@code accounts} con saldo real, en el mismo orden
	 * @param missingRealBalanceAccountIds las cuentas a conciliar que todavía no tienen saldo real, en el mismo orden
	 * @param invalidBalances    los saldos reales recibidos para cuentas que no se concilian, en el orden en que
	 *                           llegaron: no se aplican
	 */
	public record Plan(YearMonth period, LocalDate lastDay, List<ClosingBlockReason> blockers,
			List<EntryLine> pendingEntries, List<AccountLine> accounts, List<EntryEffect> effects,
			List<NewEntry> newEntries, List<Long> unresolvedEntryIds, List<InvalidResolution> invalidResolutions,
			List<Reconciliation> reconciliations, List<Long> missingRealBalanceAccountIds,
			List<InvalidBalance> invalidBalances) {

		/** Si se cumplen todas las precondiciones de RN-38. */
		public boolean preconditionsMet() {
			return blockers.isEmpty();
		}
	}

	/**
	 * Evalúa las tres precondiciones por separado e informa todas las que fallan. Un período cerrado no tiene nada que
	 * resolver ni conciliar: sus listas quedan vacías y las resoluciones y los saldos reales recibidos se ignoran. En
	 * un período abierto, los efectos de las resoluciones y las diferencias se calculan aunque no se cumplan las
	 * precondiciones.
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
			return new Plan(input.period(), lastDay, List.copyOf(blockers), List.of(), List.of(), List.of(),
					List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		}

		List<EntryLine> pending = input.entries().stream().filter(EntryLine::pending).toList();
		List<AccountLine> accounts = input.accounts().stream()
				.filter(account -> !account.openingDate().isAfter(lastDay))
				.toList();

		YearMonth next = input.period().plusMonths(1);
		List<EntryEffect> effects = new ArrayList<>();
		List<NewEntry> newEntries = new ArrayList<>();
		List<Long> unresolved = new ArrayList<>();
		for (EntryLine entry : pending) {
			ClosingResolution resolution = input.resolutions().get(entry.id());
			if (resolution == null) {
				unresolved.add(entry.id());
				continue;
			}
			EntryEffect effect = effect(entry, resolution, next);
			effects.add(effect);
			if (effect.carriedOver() != null) {
				newEntries.add(effect.carriedOver());
			}
		}

		Map<Long, EntryLine> byId = input.entries().stream()
				.collect(Collectors.toMap(EntryLine::id, Function.identity()));
		List<InvalidResolution> invalid = input.resolutions().keySet().stream()
				.filter(id -> !byId.containsKey(id) || !byId.get(id).pending())
				.map(id -> new InvalidResolution(id, byId.containsKey(id) ? InvalidResolutionReason.NOT_PENDING
						: InvalidResolutionReason.NOT_IN_PERIOD))
				.toList();

		List<Reconciliation> reconciliations = new ArrayList<>();
		List<Long> missingBalances = new ArrayList<>();
		for (AccountLine account : accounts) {
			BigDecimal real = input.realBalances().get(account.id());
			if (real == null) {
				missingBalances.add(account.id());
				continue;
			}
			reconciliations.add(reconcile(account, real, input.period(), next));
		}
		reconciliations.stream().map(Reconciliation::differenceEntry).filter(Objects::nonNull)
				.forEach(newEntries::add);

		Set<Long> reconcilable = accounts.stream().map(AccountLine::id).collect(Collectors.toSet());
		List<InvalidBalance> invalidBalances = input.realBalances().keySet().stream()
				.filter(id -> !reconcilable.contains(id))
				.map(id -> new InvalidBalance(id, InvalidBalanceReason.NOT_RECONCILABLE))
				.toList();

		return new Plan(input.period(), lastDay, List.copyOf(blockers), pending, accounts, List.copyOf(effects),
				List.copyOf(newEntries), List.copyOf(unresolved), invalid, List.copyOf(reconciliations),
				List.copyOf(missingBalances), invalidBalances);
	}

	/**
	 * RN-40, pasos 1 y 2. Las dos resoluciones consolidan con el real. Postergar saldo con pendiente mayor que 0 crea
	 * la partida de saldo postergado; con pendiente 0 no crea nada. Cerrar con lo registrado descarta el pendiente.
	 */
	private static EntryEffect effect(EntryLine entry, ClosingResolution resolution, YearMonth next) {
		BigDecimal actual = entry.amounts().actual();
		BigDecimal pending = entry.amounts().pending();
		NewEntry carriedOver = resolution == ClosingResolution.CARRY_OVER && pending.signum() > 0
				? new NewEntry(next, EntryOrigin.CARRIED_OVER, carriedOverName(entry), entry.kind(),
						entry.categoryId(), entry.categoryName(), entry.accountId(), entry.accountName(),
						entry.currency(), entry.dueDate().plusMonths(1), pending, entry.id(), null)
				: null;
		return new EntryEffect(entry.id(), resolution, actual, carriedOver);
	}

	/**
	 * RN-40, paso 3. La diferencia es real menos calculado. Si no es 0, la partida de diferencia de cierre va al período
	 * siguiente, vence el día 1 y es un ingreso si la diferencia es positiva o un gasto si es negativa, por su valor
	 * absoluto, en la cuenta conciliada. Con diferencia 0 no se crea nada.
	 */
	private static Reconciliation reconcile(AccountLine account, BigDecimal real, YearMonth period, YearMonth next) {
		BigDecimal computed = account.computedBalance();
		BigDecimal difference = real.subtract(computed).setScale(2);
		NewEntry entry = difference.signum() == 0 ? null
				: new NewEntry(next, EntryOrigin.CLOSING_DIFFERENCE, differenceName(account.name(), period),
						difference.signum() > 0 ? EntryKind.INCOME : EntryKind.EXPENSE, null, null, account.id(),
						account.name(), account.currency(), next.atDay(1), difference.abs(), null, account.id());
		return new Reconciliation(account.id(), computed, real.setScale(2), difference, entry);
	}

	/**
	 * «Diferencia de cierre: <cuenta> (<mes abreviado> <año>)», con el mes y el año del período que se cierra (D-44). Si
	 * el nombre completo supera los 100 caracteres de la columna, se recorta el nombre de la cuenta y se marca con «…»,
	 * y el mes y el año quedan intactos, para que el total sea 100.
	 */
	static String differenceName(String accountName, YearMonth period) {
		String suffix = " (" + MONTH_ABBREVIATIONS.get(period.getMonthValue() - 1) + " " + period.getYear() + ")";
		String full = DIFFERENCE_PREFIX + accountName + suffix;
		if (full.length() <= NAME_MAX_LENGTH) {
			return full;
		}
		int keep = NAME_MAX_LENGTH - DIFFERENCE_PREFIX.length() - ELLIPSIS.length() - suffix.length();
		return DIFFERENCE_PREFIX + cut(accountName, keep) + suffix;
	}

	/**
	 * «Saldo pendiente: <nombre>»; una partida que ya era un saldo postergado conserva su nombre (RN-40). Si el nombre
	 * completo supera los 100 caracteres de la columna, se recorta el original y se marca con «…», para que el total
	 * sea 100 (D-43). Se cuenta en caracteres de Java, igual que la validación de los nombres, y no se corta un par
	 * sustituto.
	 */
	static String carriedOverName(EntryLine entry) {
		if (entry.origin() == EntryOrigin.CARRIED_OVER) {
			return entry.name();
		}
		String full = CARRIED_OVER_PREFIX + entry.name();
		if (full.length() <= NAME_MAX_LENGTH) {
			return full;
		}
		int keep = NAME_MAX_LENGTH - CARRIED_OVER_PREFIX.length() - ELLIPSIS.length();
		return CARRIED_OVER_PREFIX + cut(entry.name(), keep);
	}

	/** Los primeros {@code keep} caracteres, sin espacios al final ni un par sustituto partido, más «…». */
	private static String cut(String name, int keep) {
		if (Character.isHighSurrogate(name.charAt(keep - 1))) {
			keep--;
		}
		return name.substring(0, keep).stripTrailing() + ELLIPSIS;
	}
}
