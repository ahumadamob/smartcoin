package com.smartcoin.period.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.smartcoin.account.domain.Account;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.category.domain.Category;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.OverdueRule;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.MonthTotals;
import com.smartcoin.period.domain.MonthTotals.CurrencyTotals;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vista del mes (HU-15, RN-44, D-29). Solo lee; toda consulta lleva el {@code userId} del usuario actual. */
@Service
public class PeriodViewService {

	/**
	 * Una partida en la vista del mes, con sus valores derivados. El nombre y la categoría son los del Concepto en una
	 * recurrente y los propios en las demás. Todo se lee acá, dentro de la transacción, para que la respuesta no lea
	 * asociaciones fuera de ella ({@code open-in-view: false}).
	 */
	public record EntryRow(Long id, Long budgetItemId, EntryOrigin origin, EntryKind kind, String name,
			Long categoryId, String categoryName, Long accountId, String accountName, Currency currency,
			LocalDate dueDate, Integer installmentNumber, Integer installmentsTotal, BigDecimal budgetedAmount,
			EntryAmounts amounts, boolean manual, boolean overdue) {
	}

	/**
	 * @param startPeriod   período inicial del usuario: el primero que existe
	 * @param currentPeriod período actual, del {@code Clock}
	 * @param horizon       último período que existe para el usuario (D-29)
	 */
	public record View(YearMonth period, PeriodStatus status, YearMonth startPeriod, YearMonth currentPeriod,
			YearMonth horizon, List<EntryRow> incomes, List<EntryRow> expenses, List<CurrencyTotals> totals) {
	}

	/** RN-44: por vencimiento y nombre; el nombre puede repetirse (S-19), así que desempata el orden de creación. */
	private static final Comparator<EntryRow> ORDER = Comparator.comparing(EntryRow::dueDate)
			.thenComparing(EntryRow::name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(EntryRow::id);

	private final BudgetPeriodRepository periods;
	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final Clock clock;

	public PeriodViewService(BudgetPeriodRepository periods, BudgetEntryRepository entries,
			MovementRepository movements, Clock clock) {
		this.periods = periods;
		this.entries = entries;
		this.movements = movements;
		this.clock = clock;
	}

	/** La vista del período actual (RN-05). */
	@Transactional(readOnly = true)
	public View current(long userId) {
		return view(userId, YearMonth.now(clock));
	}

	/**
	 * La vista de un período del usuario. Si no existe para él (RN-06: es anterior a su período inicial o posterior
	 * al horizonte), responde 404. Dos consultas para las partidas: todas con su Concepto, categoría y cuenta, y las
	 * sumas de movimientos de todo el período.
	 */
	@Transactional(readOnly = true)
	public View view(long userId, YearMonth periodMonth) {
		BudgetPeriod period = periods.findByUserIdAndPeriodMonth(userId, periodMonth)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "El período " + periodMonth
						+ " no existe: es anterior al período inicial o posterior al horizonte."));
		List<YearMonth> existing = periods.findPeriodMonthsByUserId(userId);
		LocalDate today = LocalDate.now(clock);

		Map<Long, BigDecimal> movementTotals = movements.sumByEntryOfPeriod(userId, period.getId()).stream()
				.collect(Collectors.toMap(EntryTotal::entryId, EntryTotal::total));
		List<EntryRow> rows = entries.findByUserIdAndPeriodIdWithDetails(userId, period.getId()).stream()
				.map(entry -> row(entry, movementTotals.get(entry.getId()), today))
				.sorted(ORDER)
				.toList();

		List<CurrencyTotals> totals = MonthTotals.calculate(rows.stream()
				.map(r -> new MonthTotals.Line(r.kind(), r.currency(), r.budgetedAmount(), r.amounts().actual(),
						r.amounts().pending(), r.amounts().forecast()))
				.toList());
		return new View(periodMonth, period.getStatus(), Collections.min(existing), YearMonth.now(clock),
				Collections.max(existing), rows.stream().filter(r -> r.kind() == EntryKind.INCOME).toList(),
				rows.stream().filter(r -> r.kind() == EntryKind.EXPENSE).toList(), totals);
	}

	/**
	 * La fila de una partida con sus valores derivados. La usa también el alta y la edición de partidas (HU-16), para
	 * que respondan con la misma forma que la vista del mes. Lee asociaciones: va dentro de la transacción.
	 *
	 * @param movementsTotal suma de sus movimientos, o {@code null} si no tiene ninguno
	 * @param today          hoy, del {@code Clock} (RN-02)
	 */
	public static EntryRow row(BudgetEntry entry, BigDecimal movementsTotal, LocalDate today) {
		BudgetItem item = entry.getBudgetItem();
		Category category = item == null ? entry.getCategory() : item.getCategory();
		Account account = entry.getAccount();
		return new EntryRow(entry.getId(), item == null ? null : item.getId(), entry.getOrigin(), entry.getKind(),
				item == null ? entry.getName() : item.getName(), category == null ? null : category.getId(),
				category == null ? null : category.getName(), account.getId(), account.getName(),
				account.getCurrency(), entry.getDueDate(), entry.getInstallmentNumber(),
				item == null ? null : item.getInstallmentsTotal(), entry.getBudgetedAmount(),
				EntryAmounts.of(entry.getBudgetedAmount(), entry.getStatus(), entry.getConsolidatedAmount(),
						movementsTotal),
				entry.isManual(), OverdueRule.isOverdue(entry.getStatus(), entry.getDueDate(), today));
	}
}
