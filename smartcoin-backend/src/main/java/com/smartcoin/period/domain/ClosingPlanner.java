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
 * y las partidas de saldo postergado que se crearían. Los saldos reales con sus diferencias se suman al {@link Input} y
 * al {@link Plan} sin cambiar lo que ya existe.
 */
public final class ClosingPlanner {

	/** Largo máximo del nombre de una partida ({@code budget_item.name}, {@code budget_entry.name}). */
	static final int NAME_MAX_LENGTH = 100;

	static final String CARRIED_OVER_PREFIX = "Saldo pendiente: ";

	private static final String ELLIPSIS = "…";

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
	 */
	public record Input(YearMonth period, PeriodStatus status, PeriodStatus previousStatus, boolean startPeriod,
			LocalDate today, List<EntryLine> entries, List<AccountLine> accounts,
			Map<Long, ClosingResolution> resolutions) {

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
		}
	}

	/**
	 * Una partida que crearía el cierre en el período siguiente. HU-31: las de saldo postergado (RN-40, paso 1).
	 *
	 * @param sourceEntryId la partida cuyo saldo se posterga
	 */
	public record NewEntry(YearMonth period, EntryOrigin origin, String name, EntryKind kind, Long categoryId,
			String categoryName, Long accountId, String accountName, Currency currency, LocalDate dueDate,
			BigDecimal budgetedAmount, Long sourceEntryId) {
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
	 * @param lastDay        último día del período: el saldo se calcula a esa fecha y desde ese día se puede cerrar
	 * @param blockers       todas las precondiciones de RN-38 que no se cumplen, en el orden de RN-38
	 * @param pendingEntries las partidas pendientes (Estimadas y Parciales), cada una necesitará una resolución
	 * @param accounts       las cuentas a conciliar: abiertas el último día del período o antes
	 * @param effects        una por cada pendiente con resolución, en el orden de {@code pendingEntries}
	 * @param newEntries     las partidas que crearía el cierre en el período siguiente, en el mismo orden
	 * @param unresolvedEntryIds las pendientes que todavía no tienen resolución, en el mismo orden
	 * @param invalidResolutions las resoluciones recibidas que no corresponden, en el orden en que llegaron: no se
	 *                           aplican
	 */
	public record Plan(YearMonth period, LocalDate lastDay, List<ClosingBlockReason> blockers,
			List<EntryLine> pendingEntries, List<AccountLine> accounts, List<EntryEffect> effects,
			List<NewEntry> newEntries, List<Long> unresolvedEntryIds, List<InvalidResolution> invalidResolutions) {

		/** Si se cumplen todas las precondiciones de RN-38. */
		public boolean preconditionsMet() {
			return blockers.isEmpty();
		}
	}

	/**
	 * Evalúa las tres precondiciones por separado e informa todas las que fallan. Un período cerrado no tiene nada que
	 * resolver ni conciliar: sus listas quedan vacías y las resoluciones recibidas se ignoran. En un período abierto,
	 * los efectos de las resoluciones se calculan aunque no se cumplan las precondiciones.
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
					List.of(), List.of(), List.of());
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

		return new Plan(input.period(), lastDay, List.copyOf(blockers), pending, accounts, List.copyOf(effects),
				List.copyOf(newEntries), List.copyOf(unresolved), invalid);
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
						entry.currency(), entry.dueDate().plusMonths(1), pending, entry.id())
				: null;
		return new EntryEffect(entry.id(), resolution, actual, carriedOver);
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
		if (Character.isHighSurrogate(entry.name().charAt(keep - 1))) {
			keep--;
		}
		return CARRIED_OVER_PREFIX + entry.name().substring(0, keep).stripTrailing() + ELLIPSIS;
	}
}
