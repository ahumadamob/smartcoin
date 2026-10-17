package com.smartcoin.report.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.smartcoin.account.service.AccountService;
import com.smartcoin.account.service.AccountService.AccountBalance;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.report.domain.ProjectionCalculator;
import com.smartcoin.report.domain.ProjectionCalculator.AccountStart;
import com.smartcoin.report.domain.ProjectionCalculator.Input;
import com.smartcoin.report.domain.ProjectionCalculator.PendingLine;
import com.smartcoin.report.domain.ProjectionCalculator.Result;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proyección de saldos por cuenta (HU-35, RN-46). Solo lee; toda consulta lleva el {@code userId} del usuario actual.
 * Cuatro consultas fijas, sin importar cuántas cuentas, meses o partidas haya: los períodos (para el horizonte), los
 * saldos a hoy ({@link AccountService#balancesAt}), las partidas pendientes del usuario con vencimiento hasta el
 * último día proyectado y la suma de los movimientos de esas partidas.
 */
@Service
public class ProjectionService {

	private final AccountService accounts;
	private final MovementRepository movements;
	private final BudgetEntryRepository entries;
	private final BudgetPeriodRepository periods;
	private final Clock clock;

	public ProjectionService(AccountService accounts, MovementRepository movements, BudgetEntryRepository entries,
			BudgetPeriodRepository periods, Clock clock) {
		this.accounts = accounts;
		this.movements = movements;
		this.entries = entries;
		this.periods = periods;
		this.clock = clock;
	}

	/**
	 * La proyección desde el mes actual hasta {@code until} (por defecto, el horizonte). {@code until} no puede ser
	 * anterior al mes actual ni posterior al horizonte: 400 {@code VALIDATION_ERROR} en el campo {@code until}.
	 */
	@Transactional(readOnly = true)
	public Result projection(long userId, YearMonth until) {
		LocalDate today = LocalDate.now(clock);
		YearMonth current = YearMonth.from(today);
		List<YearMonth> existing = periods.findPeriodMonthsByUserId(userId);
		if (existing.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "El usuario no tiene períodos.");
		}
		YearMonth horizon = Collections.max(existing);
		YearMonth last = until == null ? horizon : until;
		if (last.isBefore(current)) {
			throw BusinessException.invalidField("until",
					"El mes hasta no puede ser anterior al mes actual (" + current + ").");
		}
		if (last.isAfter(horizon)) {
			throw BusinessException.invalidField("until",
					"El mes hasta no puede ser posterior al horizonte (" + horizon + ").");
		}

		List<AccountStart> starts = accounts.balancesAt(userId, today).stream()
				.map(ProjectionService::start)
				.toList();

		LocalDate dueUntil = last.atEndOfMonth();
		Map<Long, BigDecimal> movementTotals = movements
				.sumByPendingEntriesUntil(userId, StoredEntryStatus.PENDING, dueUntil).stream()
				.collect(Collectors.toMap(EntryTotal::entryId, EntryTotal::total));
		List<PendingLine> pending = entries.findPendingUntil(userId, StoredEntryStatus.PENDING, dueUntil).stream()
				.map(entry -> {
					EntryAmounts amounts = EntryAmounts.of(entry.getBudgetedAmount(), entry.getStatus(),
							entry.getConsolidatedAmount(), movementTotals.get(entry.getId()));
					return new PendingLine(entry.getAccount().getId(), entry.getKind(), entry.getDueDate(),
							amounts.pending());
				})
				.toList();

		return ProjectionCalculator.calculate(new Input(today, last, starts, pending));
	}

	private static AccountStart start(AccountBalance balance) {
		return new AccountStart(balance.account().getId(), balance.account().getName(),
				balance.account().getCurrency(), balance.balance());
	}
}
