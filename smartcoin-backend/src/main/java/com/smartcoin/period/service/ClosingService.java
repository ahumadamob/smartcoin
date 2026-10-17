package com.smartcoin.period.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.service.AccountService;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.category.domain.Category;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.period.domain.AccountClosing;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.ClosingBlockReason;
import com.smartcoin.period.domain.ClosingPlanner;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.EntryEffect;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.ClosingPlanner.InvalidBalance;
import com.smartcoin.period.domain.ClosingPlanner.InvalidResolution;
import com.smartcoin.period.domain.ClosingPlanner.NewEntry;
import com.smartcoin.period.domain.ClosingPlanner.Plan;
import com.smartcoin.period.domain.ClosingPlanner.Reconciliation;
import com.smartcoin.period.domain.InvalidResolutionReason;
import com.smartcoin.period.domain.PeriodRange;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.AccountClosingRepository;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService.LoadedRow;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierre de mes (HU-30 a HU-33, RN-38 a RN-43). HU-30: la simulación, que no modifica nada; HU-31 le suma las
 * resoluciones de las pendientes y HU-32 los saldos reales con sus diferencias. HU-33: el cierre efectivo, que vuelve a
 * armar el mismo plan con {@link ClosingPlanner} (RN-43), rechaza lo que la simulación solo informa y aplica los
 * efectos de RN-40 en una sola transacción. Toda consulta lleva el {@code userId} del usuario actual.
 */
@Service
public class ClosingService {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/** La resolución elegida para una partida pendiente (RN-39), tal como llega en el pedido. */
	public record Resolution(long entryId, ClosingResolution resolution) {
	}

	/** El saldo real informado para una cuenta (RN-39), tal como llega en el pedido. */
	public record RealBalance(long accountId, BigDecimal amount) {
	}

	/** Una precondición que no se cumple, con su explicación en español. */
	public record Blocker(ClosingBlockReason reason, String detail) {
	}

	/**
	 * La simulación del cierre (RN-43).
	 *
	 * @param firstOpenPeriod primer período abierto del usuario (RN-08): el que hay que cerrar primero
	 * @param blockers        las precondiciones que fallan, en el orden de RN-38, con su explicación
	 */
	public record Preview(Plan plan, PeriodStatus status, YearMonth firstOpenPeriod, List<Blocker> blockers) {
	}

	/** Una partida pendiente consolidada por el cierre, con su resolución (RN-40, pasos 1 y 2). */
	public record ConsolidatedEntry(long entryId, String name, ClosingResolution resolution,
			BigDecimal consolidatedAmount) {
	}

	/** Una partida creada por el cierre en el período siguiente, con su id ya asignado. */
	public record CreatedEntry(long id, NewEntry entry) {
	}

	/**
	 * Un cierre de cuenta guardado (RN-40, paso 3).
	 *
	 * @param differenceEntryId la partida de diferencia de cierre creada; {@code null} con diferencia 0
	 */
	public record SavedClosing(long id, AccountLine account, Reconciliation reconciliation, Long differenceEntryId) {
	}

	/**
	 * Lo que aplicó el cierre (HU-33, D-45), en el orden de RN-40.
	 *
	 * @param closedAt el mismo instante que la fecha de consolidación de las partidas consolidadas por el cierre
	 * @param createdEntries primero las de saldo postergado y después las de diferencia de cierre, como en el plan
	 */
	public record Result(YearMonth period, Instant closedAt, YearMonth nextPeriod,
			List<ConsolidatedEntry> consolidatedEntries, List<CreatedEntry> createdEntries,
			List<SavedClosing> accountClosings) {
	}

	private final BudgetPeriodRepository periods;
	private final BudgetEntryRepository entries;
	private final AccountClosingRepository closings;
	private final PeriodViewService periodView;
	private final AccountService accounts;
	private final Clock clock;

	public ClosingService(BudgetPeriodRepository periods, BudgetEntryRepository entries,
			AccountClosingRepository closings, PeriodViewService periodView, AccountService accounts, Clock clock) {
		this.periods = periods;
		this.entries = entries;
		this.closings = closings;
		this.periodView = periodView;
		this.accounts = accounts;
		this.clock = clock;
	}

	/**
	 * Qué falta para cerrar un período y qué pasaría con las resoluciones y los saldos reales recibidos, sin modificar
	 * nada (HU-30 a HU-32). Una partida repetida en las resoluciones, o una cuenta repetida en los saldos reales, es un
	 * pedido mal formado: 400 en {@code resolutions} o {@code realBalances}, antes de leer nada. Si el período no
	 * existe para el usuario (RN-06), 404. Las resoluciones que no corresponden a una pendiente del período no son un
	 * error: se informan en el plan y no se aplican (D-43); lo mismo con un saldo real de una cuenta que no se concilia
	 * (D-44). El saldo calculado no depende de las resoluciones: solo consolidan, no registran movimientos. Las partidas y sus montos salen de las mismas consultas que
	 * la vista del mes, y los saldos de las cuatro consultas agrupadas de {@link AccountService#balancesAt}, al último
	 * día del período. Un período cerrado no carga ni partidas ni saldos: no tiene nada que resolver.
	 */
	@Transactional(readOnly = true)
	public Preview preview(long userId, YearMonth month, List<Resolution> resolutions,
			List<RealBalance> realBalances) {
		Map<Long, ClosingResolution> byEntry = byEntry(resolutions);
		Map<Long, BigDecimal> byAccount = byAccount(realBalances);
		BudgetPeriod period = periods.findByUserIdAndPeriodMonth(userId, month)
				.orElseThrow(() -> notFound(month));
		return evaluate(userId, period, byEntry, byAccount).preview();
	}

	/**
	 * Cierra el período (HU-33, RN-38 a RN-40). Arma el mismo plan que la simulación y, si no falta ni sobra nada,
	 * aplica sus efectos en una sola transacción: si algo falla, no cambia nada. No hay forma de reabrirlo (D-23).
	 *
	 * <p>Orden de los errores, el primero que falla (D-45): una partida o una cuenta repetida (400, antes de leer
	 * nada); el período (404, RN-06); la primera precondición de RN-38 ({@code PERIOD_CLOSED},
	 * {@code PREVIOUS_PERIOD_OPEN}, {@code PERIOD_NOT_FINISHED}); las resoluciones que no corresponden (400 en
	 * {@code resolutions}); los saldos reales que no corresponden (400 en {@code realBalances}); las pendientes sin
	 * resolución (409 {@code UNRESOLVED_PENDING_ENTRIES} con {@code entries}); y las cuentas sin saldo real (409
	 * {@code MISSING_REAL_BALANCE} con {@code accounts}).
	 *
	 * <p>Toma el bloqueo de escritura del período como primera lectura: un segundo cierre del mismo período espera y lo
	 * ve cerrado, y el cierre del mes siguiente lee el estado de este después de su propio bloqueo, así que nunca lo
	 * ve cerrado a medias.
	 */
	@Transactional
	public Result close(long userId, YearMonth month, List<Resolution> resolutions, List<RealBalance> realBalances) {
		Map<Long, ClosingResolution> byEntry = byEntry(resolutions);
		Map<Long, BigDecimal> byAccount = byAccount(realBalances);
		// Primera lectura: el bloqueo del período, antes de la instantánea de la transacción.
		BudgetPeriod period = periods.lockByUserIdAndPeriodMonth(userId, month)
				.orElseThrow(() -> notFound(month));
		Evaluation evaluation = evaluate(userId, period, byEntry, byAccount);
		Preview preview = evaluation.preview();
		Plan plan = preview.plan();
		if (!preview.blockers().isEmpty()) {
			Blocker first = preview.blockers().getFirst();
			throw new BusinessException(first.reason().code(), first.detail());
		}
		if (!plan.invalidResolutions().isEmpty()) {
			throw BusinessException.invalidField("resolutions", invalidResolutionsDetail(plan));
		}
		if (!plan.invalidBalances().isEmpty()) {
			throw BusinessException.invalidField("realBalances", invalidBalancesDetail(plan));
		}
		if (!plan.unresolvedEntryIds().isEmpty()) {
			int count = plan.unresolvedEntryIds().size();
			throw new BusinessException(ErrorCode.UNRESOLVED_PENDING_ENTRIES, (count == 1
					? "Falta resolver 1 partida pendiente"
					: "Faltan resolver " + count + " partidas pendientes")
					+ ": elegí «Postergar saldo» o «Cerrar con lo registrado» en cada una.", plan.unresolvedEntryIds());
		}
		if (!plan.missingRealBalanceAccountIds().isEmpty()) {
			int count = plan.missingRealBalanceAccountIds().size();
			throw BusinessException.withAccounts(ErrorCode.MISSING_REAL_BALANCE, (count == 1
					? "Falta el saldo real de 1 cuenta"
					: "Falta el saldo real de " + count + " cuentas")
					+ ": cargá el saldo que figura en el banco o la billetera al " + plan.lastDay().format(DATE) + ".",
					plan.missingRealBalanceAccountIds());
		}
		return apply(userId, period, evaluation);
	}

	/**
	 * Los efectos de RN-40, en su orden (D-45): consolidar cada pendiente con su real y su resolución, sin propagar ni
	 * tocar el Concepto, su monto vigente, el presupuestado ni la marca de editada; crear las partidas de saldo
	 * postergado; guardar un cierre de cuenta por cada cuenta conciliada, también con diferencia 0; crear las partidas
	 * de diferencia, referidas a su cierre de cuenta, que ya existe; y cerrar el período. Las claves son
	 * {@code IDENTITY}: los {@code INSERT} salen en este orden. Un solo instante del {@code Clock} para todo.
	 */
	private Result apply(long userId, BudgetPeriod period, Evaluation evaluation) {
		Plan plan = evaluation.preview().plan();
		Instant now = clock.instant();
		YearMonth nextMonth = plan.period().plusMonths(1);
		BudgetPeriod next = periods.findByUserIdAndPeriodMonth(userId, nextMonth)
				.filter(candidate -> candidate.getStatus() == PeriodStatus.OPEN)
				.orElseThrow(() -> new IllegalStateException("Falta el período abierto siguiente a " + plan.period()));

		Map<Long, EntryLine> lines = plan.pendingEntries().stream()
				.collect(Collectors.toMap(EntryLine::id, line -> line));
		List<BudgetEntry> consolidated = new ArrayList<>();
		List<ConsolidatedEntry> consolidatedEntries = new ArrayList<>();
		List<BudgetEntry> carriedOver = new ArrayList<>();
		List<NewEntry> carriedOverPlan = new ArrayList<>();
		for (EntryEffect effect : plan.effects()) {
			BudgetEntry entry = evaluation.entries().get(effect.entryId());
			entry.setStatus(StoredEntryStatus.CONSOLIDATED);
			entry.setConsolidatedAmount(effect.consolidatedAmount());
			entry.setConsolidatedAt(now);
			entry.setClosingResolution(effect.resolution());
			consolidated.add(entry);
			consolidatedEntries.add(new ConsolidatedEntry(entry.getId(), lines.get(entry.getId()).name(),
					effect.resolution(), effect.consolidatedAmount()));
			if (effect.carriedOver() != null) {
				BudgetEntry created = newEntry(userId, next, effect.carriedOver(), entry.getAccount(),
						categoryOf(entry));
				created.setSourceEntry(entry);
				carriedOver.add(created);
				carriedOverPlan.add(effect.carriedOver());
			}
		}
		entries.saveAll(consolidated);
		entries.saveAll(carriedOver);

		List<AccountClosing> accountClosings = new ArrayList<>();
		for (Reconciliation reconciliation : plan.reconciliations()) {
			AccountClosing closing = new AccountClosing();
			closing.setUserId(userId);
			closing.setPeriod(period);
			closing.setAccount(evaluation.accounts().get(reconciliation.accountId()));
			closing.setComputedBalance(reconciliation.computedBalance());
			closing.setRealBalance(reconciliation.realBalance());
			closing.setDifference(reconciliation.difference());
			accountClosings.add(closing);
		}
		closings.saveAll(accountClosings);

		List<BudgetEntry> differences = new ArrayList<>();
		List<NewEntry> differencesPlan = new ArrayList<>();
		Map<Long, BudgetEntry> differenceByAccount = new LinkedHashMap<>();
		for (int i = 0; i < plan.reconciliations().size(); i++) {
			NewEntry planned = plan.reconciliations().get(i).differenceEntry();
			if (planned == null) {
				continue;
			}
			AccountClosing closing = accountClosings.get(i);
			BudgetEntry created = newEntry(userId, next, planned, closing.getAccount(), null);
			created.setSourceClosing(closing);
			differences.add(created);
			differencesPlan.add(planned);
			differenceByAccount.put(planned.sourceAccountId(), created);
		}
		entries.saveAll(differences);

		period.setStatus(PeriodStatus.CLOSED);
		period.setClosedAt(now);
		periods.save(period);

		List<CreatedEntry> createdEntries = new ArrayList<>();
		for (int i = 0; i < carriedOver.size(); i++) {
			createdEntries.add(new CreatedEntry(carriedOver.get(i).getId(), carriedOverPlan.get(i)));
		}
		for (int i = 0; i < differences.size(); i++) {
			createdEntries.add(new CreatedEntry(differences.get(i).getId(), differencesPlan.get(i)));
		}
		Map<Long, AccountLine> accountLines = plan.accounts().stream()
				.collect(Collectors.toMap(AccountLine::id, line -> line));
		List<SavedClosing> savedClosings = new ArrayList<>();
		for (int i = 0; i < accountClosings.size(); i++) {
			Reconciliation reconciliation = plan.reconciliations().get(i);
			BudgetEntry difference = differenceByAccount.get(reconciliation.accountId());
			savedClosings.add(new SavedClosing(accountClosings.get(i).getId(),
					accountLines.get(reconciliation.accountId()), reconciliation,
					difference == null ? null : difference.getId()));
		}
		return new Result(plan.period(), now, nextMonth, List.copyOf(consolidatedEntries), List.copyOf(createdEntries),
				List.copyOf(savedClosings));
	}

	/** Una partida sin Concepto, pendiente y sin editar, en el período siguiente (RN-40). */
	private static BudgetEntry newEntry(long userId, BudgetPeriod next, NewEntry planned, Account account,
			Category category) {
		BudgetEntry entry = new BudgetEntry();
		entry.setUserId(userId);
		entry.setPeriod(next);
		entry.setOrigin(planned.origin());
		entry.setName(planned.name());
		entry.setKind(planned.kind());
		entry.setCategory(category);
		entry.setAccount(account);
		entry.setDueDate(planned.dueDate());
		entry.setBudgetedAmount(planned.budgetedAmount());
		entry.setManual(false);
		entry.setStatus(StoredEntryStatus.PENDING);
		return entry;
	}

	/** La categoría que se muestra: la del Concepto en una recurrente (RN-40, paso 1). */
	private static Category categoryOf(BudgetEntry entry) {
		BudgetItem item = entry.getBudgetItem();
		return item == null ? entry.getCategory() : item.getCategory();
	}

	private static String invalidResolutionsDetail(Plan plan) {
		return "Hay resoluciones que no corresponden a una partida pendiente de " + plan.period() + ": "
				+ plan.invalidResolutions().stream()
						.map(ClosingService::describe)
						.collect(Collectors.joining("; "))
				+ ".";
	}

	private static String describe(InvalidResolution invalid) {
		return "la partida " + invalid.entryId() + (invalid.reason() == InvalidResolutionReason.NOT_PENDING
				? " ya está consolidada"
				: " no es del período");
	}

	private static String invalidBalancesDetail(Plan plan) {
		return "Hay saldos reales de cuentas que no se concilian en " + plan.period() + " (abiertas después del "
				+ plan.lastDay().format(DATE) + " o que no existen): "
				+ plan.invalidBalances().stream()
						.map(InvalidBalance::accountId)
						.map(String::valueOf)
						.collect(Collectors.joining(", "))
				+ ".";
	}

	/**
	 * El plan de un período ya leído, con las entidades de sus partidas y de sus cuentas, para que el cierre modifique
	 * las mismas que evaluó.
	 */
	private record Evaluation(Preview preview, Map<Long, BudgetEntry> entries, Map<Long, Account> accounts) {
	}

	private Evaluation evaluate(long userId, BudgetPeriod period, Map<Long, ClosingResolution> byEntry,
			Map<Long, BigDecimal> byAccount) {
		YearMonth month = period.getPeriodMonth();
		YearMonth start = Collections.min(periods.findPeriodMonthsByUserId(userId));
		boolean isStart = month.equals(start);
		PeriodStatus previousStatus = isStart ? null
				: periods.findByUserIdAndPeriodMonth(userId, month.minusMonths(1))
						.map(BudgetPeriod::getStatus)
						.orElseThrow(() -> new IllegalStateException("Falta el período anterior a " + month));
		YearMonth lastClosed = periods.findFirstByUserIdAndStatusOrderByPeriodMonthDesc(userId, PeriodStatus.CLOSED)
				.map(BudgetPeriod::getPeriodMonth)
				.orElse(null);
		YearMonth firstOpen = PeriodRange.firstOpen(start, lastClosed);

		boolean open = period.getStatus() == PeriodStatus.OPEN;
		List<LoadedRow> loaded = open ? periodView.loadedRows(userId, period) : List.of();
		List<AccountService.AccountBalance> balances = open
				? accounts.balancesAt(userId, month.atEndOfMonth())
				: List.of();

		Plan plan = ClosingPlanner.plan(new ClosingPlanner.Input(month, period.getStatus(), previousStatus, isStart,
				LocalDate.now(clock), loaded.stream().map(ClosingService::entryLine).toList(),
				balances.stream().map(ClosingService::accountLine).toList(), byEntry, byAccount));
		List<Blocker> blockers = plan.blockers().stream()
				.map(reason -> new Blocker(reason, detail(reason, plan, firstOpen)))
				.toList();
		Map<Long, BudgetEntry> entities = new LinkedHashMap<>();
		loaded.forEach(row -> entities.put(row.entry().getId(), row.entry()));
		Map<Long, Account> accountEntities = new LinkedHashMap<>();
		balances.forEach(balance -> accountEntities.put(balance.account().getId(), balance.account()));
		return new Evaluation(new Preview(plan, period.getStatus(), firstOpen, blockers), entities, accountEntities);
	}

	private static BusinessException notFound(YearMonth month) {
		return new BusinessException(ErrorCode.NOT_FOUND, "El período " + month
				+ " no existe: es anterior al período inicial o posterior al horizonte.");
	}

	private static Map<Long, ClosingResolution> byEntry(List<Resolution> resolutions) {
		Map<Long, ClosingResolution> byEntry = new LinkedHashMap<>();
		for (Resolution resolution : resolutions) {
			if (byEntry.putIfAbsent(resolution.entryId(), resolution.resolution()) != null) {
				throw BusinessException.invalidField("resolutions",
						"La partida " + resolution.entryId() + " tiene más de una resolución.");
			}
		}
		return byEntry;
	}

	private static Map<Long, BigDecimal> byAccount(List<RealBalance> realBalances) {
		Map<Long, BigDecimal> byAccount = new LinkedHashMap<>();
		for (RealBalance balance : realBalances) {
			if (byAccount.putIfAbsent(balance.accountId(), balance.amount()) != null) {
				throw BusinessException.invalidField("realBalances",
						"La cuenta " + balance.accountId() + " tiene más de un saldo real.");
			}
		}
		return byAccount;
	}

	/** El nombre y la categoría son los que muestra la vista del mes: los del Concepto en una recurrente. */
	private static EntryLine entryLine(LoadedRow loaded) {
		PeriodViewService.EntryRow row = loaded.row();
		return new EntryLine(row.id(), row.origin(), row.name(), row.kind(), row.categoryId(), row.categoryName(),
				row.accountId(), row.accountName(), row.currency(), row.dueDate(), row.budgetedAmount(),
				row.amounts());
	}

	private static AccountLine accountLine(AccountService.AccountBalance balance) {
		Account account = balance.account();
		return new AccountLine(account.getId(), account.getName(), account.getCurrency(), account.getOpeningDate(),
				balance.balance());
	}

	private static String detail(ClosingBlockReason reason, Plan plan, YearMonth firstOpen) {
		return switch (reason) {
			case PERIOD_CLOSED -> "El período " + plan.period() + " ya está cerrado.";
			case PREVIOUS_PERIOD_OPEN -> "El período anterior, " + plan.period().minusMonths(1)
					+ ", todavía está abierto. Los meses se cierran en orden: el primero abierto es " + firstOpen + ".";
			case PERIOD_NOT_FINISHED -> "El período " + plan.period() + " termina el " + plan.lastDay().format(DATE)
					+ ": se puede cerrar desde ese día.";
		};
	}
}
