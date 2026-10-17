package com.smartcoin.report.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.service.AccountService;
import com.smartcoin.account.service.AccountService.AccountBalance;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.report.domain.CashFlowCalculator;
import com.smartcoin.report.domain.CashFlowCalculator.AccountOpening;
import com.smartcoin.report.domain.CashFlowCalculator.Input;
import com.smartcoin.report.domain.CashFlowCalculator.MovementLine;
import com.smartcoin.report.domain.CashFlowCalculator.PendingLine;
import com.smartcoin.report.domain.CashFlowCalculator.Result;
import com.smartcoin.report.domain.CashFlowCalculator.TransferLine;
import com.smartcoin.report.domain.CashFlowRange;
import com.smartcoin.report.domain.CashFlowRange.Range;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.transfer.repository.TransferRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flujo de caja por fecha (HU-34, RN-45, D-46). Solo lee; toda consulta lleva el {@code userId} del usuario actual.
 * Carga los datos del rango y los entrega a {@link CashFlowCalculator}: nunca hace una consulta por fila.
 */
@Service
public class CashFlowService {

	/** El flujo de una moneda para un rango ya resuelto. */
	public record CashFlow(Currency currency, LocalDate from, LocalDate to, Result result) {
	}

	private final AccountService accounts;
	private final MovementRepository movements;
	private final TransferRepository transfers;
	private final BudgetEntryRepository entries;
	private final BudgetPeriodRepository periods;
	private final Clock clock;

	public CashFlowService(AccountService accounts, MovementRepository movements, TransferRepository transfers,
			BudgetEntryRepository entries, BudgetPeriodRepository periods, Clock clock) {
		this.accounts = accounts;
		this.movements = movements;
		this.transfers = transfers;
		this.entries = entries;
		this.periods = periods;
		this.clock = clock;
	}

	/**
	 * El flujo de caja de {@code currency} entre {@code from} y {@code to}, completando los extremos que falten y
	 * verificando los límites ({@link CashFlowRange}, 400). Consultas: períodos del usuario, los saldos de las cuentas
	 * (las cuatro sumas agrupadas de HU-08), y una por tipo de fila: movimientos, transferencias entre monedas,
	 * partidas pendientes y la suma de los movimientos de esas partidas.
	 */
	@Transactional(readOnly = true)
	public CashFlow cashFlow(long userId, LocalDate from, LocalDate to, Currency currency) {
		LocalDate today = LocalDate.now(clock);
		List<YearMonth> existing = periods.findPeriodMonthsByUserId(userId);
		if (existing.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "El usuario no tiene períodos.");
		}
		Range range = CashFlowRange.resolve(from, to, today, Collections.min(existing), Collections.max(existing));

		// Cuentas de la moneda: las abiertas antes del rango aportan su saldo real al día anterior (RN-35); las que abren
		// dentro del rango, su saldo inicial como una fila en su fecha de apertura (D-46). Las abiertas después del
		// rango las ignora la regla.
		List<AccountBalance> balances = accounts.balancesAt(userId, range.from().minusDays(1)).stream()
				.filter(b -> b.account().getCurrency() == currency)
				.toList();
		BigDecimal opening = balances.stream()
				.filter(b -> b.account().getOpeningDate().isBefore(range.from()))
				.map(AccountBalance::balance)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		List<AccountOpening> openings = balances.stream()
				.map(AccountBalance::account)
				.filter(a -> !a.getOpeningDate().isBefore(range.from()))
				.map(a -> new AccountOpening(a.getId(), a.getName(), a.getCurrency(), a.getOpeningDate(),
						a.getInitialBalance()))
				.toList();

		List<MovementLine> movementLines = movements.findForCashFlow(userId, currency, range.from(), range.to())
				.stream()
				.map(m -> new MovementLine(m.getId(), m.getMovementDate(), m.getEntry().getKind(),
						entryName(m.getEntry()), m.getAccount().getName(), m.getAccount().getCurrency(),
						m.getAmount()))
				.toList();
		List<TransferLine> transferLines = transfers
				.findCrossCurrencyForCashFlow(userId, range.from(), range.to()).stream()
				.map(t -> new TransferLine(t.getId(), t.getTransferDate(), t.getSourceAccount().getName(),
						t.getSourceAccount().getCurrency(), t.getSourceAmount(), t.getTargetAccount().getName(),
						t.getTargetAccount().getCurrency(), t.getTargetAmount()))
				.toList();
		List<PendingLine> pendingLines = pendingLines(userId, currency, range.to());

		Result result = CashFlowCalculator.calculate(new Input(currency, range.from(), range.to(), today, opening,
				openings, movementLines, transferLines, pendingLines));
		return new CashFlow(currency, range.from(), range.to(), result);
	}

	/**
	 * Las partidas pendientes de la moneda con vencimiento hasta {@code to}, con su pendiente de RN-17. Las que vencieron
	 * antes de hoy tienen fecha efectiva hoy: si el rango termina antes, la regla las deja afuera, así que alcanza con
	 * pedir hasta {@code to} (todas las que vencen después de {@code to} tienen fecha efectiva posterior).
	 */
	private List<PendingLine> pendingLines(long userId, Currency currency, LocalDate to) {
		Map<Long, BigDecimal> movementTotals = movements
				.sumByPendingEntriesDueUntil(userId, StoredEntryStatus.PENDING, currency, to).stream()
				.collect(Collectors.toMap(EntryTotal::entryId, EntryTotal::total));
		return entries.findPendingForCashFlow(userId, StoredEntryStatus.PENDING, currency, to).stream()
				.map(entry -> {
					EntryAmounts amounts = EntryAmounts.of(entry.getBudgetedAmount(), entry.getStatus(),
							entry.getConsolidatedAmount(), movementTotals.get(entry.getId()));
					Account account = entry.getAccount();
					return new PendingLine(entry.getId(), entry.getKind(), entryName(entry), account.getName(),
							account.getCurrency(), entry.getDueDate(), amounts.pending());
				})
				.toList();
	}

	/** El nombre del Concepto en una partida recurrente y el propio en las demás (como la vista del mes). */
	private static String entryName(BudgetEntry entry) {
		return entry.getBudgetItem() == null ? entry.getName() : entry.getBudgetItem().getName();
	}
}
