package com.smartcoin.period.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.service.AccountService;
import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.ClosingBlockReason;
import com.smartcoin.period.domain.ClosingPlanner;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.ClosingPlanner.Plan;
import com.smartcoin.period.domain.PeriodRange;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierre de mes (HU-30 a HU-33, RN-38 a RN-43). HU-30: la simulación, que no modifica nada; HU-31 le suma las
 * resoluciones de las pendientes. Toda consulta lleva el {@code userId} del usuario actual.
 */
@Service
public class ClosingService {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/** La resolución elegida para una partida pendiente (RN-39), tal como llega en el pedido. */
	public record Resolution(long entryId, ClosingResolution resolution) {
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

	private final BudgetPeriodRepository periods;
	private final PeriodViewService periodView;
	private final AccountService accounts;
	private final Clock clock;

	public ClosingService(BudgetPeriodRepository periods, PeriodViewService periodView, AccountService accounts,
			Clock clock) {
		this.periods = periods;
		this.periodView = periodView;
		this.accounts = accounts;
		this.clock = clock;
	}

	/**
	 * Qué falta para cerrar un período y qué pasaría con las resoluciones recibidas, sin modificar nada (HU-30, HU-31).
	 * Una partida repetida en las resoluciones es un pedido mal formado: 400 en {@code resolutions}. Si el período no
	 * existe para el usuario (RN-06), 404. Las resoluciones que no corresponden a una pendiente del período no son un
	 * error: se informan en el plan y no se aplican (D-43). Las partidas y sus montos salen de las mismas consultas que
	 * la vista del mes, y los saldos de las cuatro consultas agrupadas de {@link AccountService#balancesAt}, al último
	 * día del período. Un período cerrado no carga ni partidas ni saldos: no tiene nada que resolver.
	 */
	@Transactional(readOnly = true)
	public Preview preview(long userId, YearMonth month, List<Resolution> resolutions) {
		Map<Long, ClosingResolution> byEntry = byEntry(resolutions);
		BudgetPeriod period = periods.findByUserIdAndPeriodMonth(userId, month)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "El período " + month
						+ " no existe: es anterior al período inicial o posterior al horizonte."));
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
		List<EntryLine> entries = open ? entryLines(userId, period) : List.of();
		List<AccountLine> accountLines = open ? accountLines(userId, month.atEndOfMonth()) : List.of();

		Plan plan = ClosingPlanner.plan(new ClosingPlanner.Input(month, period.getStatus(), previousStatus, isStart,
				LocalDate.now(clock), entries, accountLines, byEntry));
		List<Blocker> blockers = plan.blockers().stream()
				.map(reason -> new Blocker(reason, detail(reason, plan, firstOpen)))
				.toList();
		return new Preview(plan, period.getStatus(), firstOpen, blockers);
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

	/** El nombre y la categoría son los que muestra la vista del mes: los del Concepto en una recurrente. */
	private List<EntryLine> entryLines(long userId, BudgetPeriod period) {
		return periodView.rows(userId, period).stream()
				.map(row -> new EntryLine(row.id(), row.origin(), row.name(), row.kind(), row.categoryId(),
						row.categoryName(), row.accountId(), row.accountName(), row.currency(), row.dueDate(),
						row.budgetedAmount(), row.amounts()))
				.toList();
	}

	private List<AccountLine> accountLines(long userId, LocalDate lastDay) {
		return accounts.balancesAt(userId, lastDay).stream()
				.map(balance -> {
					Account account = balance.account();
					return new AccountLine(account.getId(), account.getName(), account.getCurrency(),
							account.getOpeningDate(), balance.balance());
				})
				.toList();
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
