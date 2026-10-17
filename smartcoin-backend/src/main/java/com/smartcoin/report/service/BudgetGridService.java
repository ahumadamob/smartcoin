package com.smartcoin.report.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.MonthTotals;
import com.smartcoin.period.domain.MonthTotals.CurrencyTotals;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.report.domain.BudgetGridRange;
import com.smartcoin.report.domain.BudgetGridRange.Range;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vista de varios meses (HU-36, RN-44, D-48). Solo lee; toda consulta lleva el {@code userId} del usuario actual.
 * Cuatro consultas fijas, sin importar cuántos Conceptos, meses o partidas haya: los meses que existen (para el
 * período inicial y el horizonte), los períodos del rango (para su estado), las partidas del rango con su Concepto,
 * categoría y cuenta, y las sumas de movimientos de todas ellas. Cada celda sale de
 * {@link PeriodViewService#row}, la misma fila de la vista del mes, y cada total de {@link MonthTotals}.
 */
@Service
public class BudgetGridService {

	/** Un mes de la grilla; {@code status} deja marcar los cerrados. */
	public record Month(YearMonth period, PeriodStatus status) {
	}

	/**
	 * Lo que hay en una fila durante un mes. Con una sola partida trae su id, su estado y si está editada; con varias
	 * (las filas agrupadas de partidas sin Concepto) trae la cantidad y el estimado sumado, sin id ni estado, y
	 * {@code manual} y {@code consolidated} valen si alguna está editada y si todas están consolidadas.
	 */
	public record Cell(Long entryId, int entryCount, Currency currency, BigDecimal forecast, EntryStatus status,
			boolean manual, boolean consolidated) {
	}

	/**
	 * Una fila de la grilla. De un Concepto: {@code budgetItemId}, {@code name} y la moneda de su cuenta por defecto.
	 * Agrupada: {@code origin} y la moneda, sin id ni nombre. {@code cells} tiene un elemento por mes, en el orden de
	 * {@code months}, y {@code null} donde no hay partida.
	 */
	public record Row(Long budgetItemId, EntryOrigin origin, String name, EntryKind kind, Currency currency,
			List<Cell> cells) {
	}

	/** Totales de un mes: uno por moneda con partidas, en el orden ARS, USD. */
	public record MonthTotal(YearMonth period, List<CurrencyTotals> totals) {
	}

	public record Grid(YearMonth from, YearMonth to, YearMonth currentPeriod, YearMonth startPeriod,
			YearMonth horizon, List<Month> months, List<Row> rows, List<MonthTotal> totals) {
	}

	/** Una partida con su fila de la vista del mes y la moneda de la cuenta por defecto de su Concepto. */
	private record Built(EntryRow row, Currency itemCurrency) {
	}

	private static final Comparator<Row> ROW_ORDER = Comparator.comparing(Row::kind)
			.thenComparing(row -> row.budgetItemId() == null)
			.thenComparing(Row::name, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
			.thenComparing(Row::budgetItemId, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(Row::origin, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(Row::currency);

	private final BudgetPeriodRepository periods;
	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final Clock clock;

	public BudgetGridService(BudgetPeriodRepository periods, BudgetEntryRepository entries,
			MovementRepository movements, Clock clock) {
		this.periods = periods;
		this.entries = entries;
		this.movements = movements;
		this.clock = clock;
	}

	/**
	 * La grilla de {@code from} a {@code to}, inclusive (ver {@link BudgetGridRange}). Un rango fuera de los períodos
	 * del usuario, invertido o de más de 24 meses es 400 {@code VALIDATION_ERROR} en {@code from} o {@code to}.
	 */
	@Transactional(readOnly = true)
	public Grid grid(long userId, YearMonth from, YearMonth to) {
		LocalDate today = LocalDate.now(clock);
		YearMonth current = YearMonth.from(today);
		List<YearMonth> existing = periods.findPeriodMonthsByUserId(userId);
		if (existing.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "El usuario no tiene períodos.");
		}
		YearMonth start = Collections.min(existing);
		YearMonth horizon = Collections.max(existing);
		Range range = BudgetGridRange.resolve(from, to, current, start, horizon);

		List<YearMonth> monthList = new ArrayList<>();
		for (YearMonth m = range.from(); !m.isAfter(range.to()); m = m.plusMonths(1)) {
			monthList.add(m);
		}
		Map<YearMonth, PeriodStatus> statuses = periods.findByUserIdAndPeriodMonthIn(userId, monthList).stream()
				.collect(Collectors.toMap(BudgetPeriod::getPeriodMonth, BudgetPeriod::getStatus));
		List<Month> months = monthList.stream()
				.map(m -> new Month(m, statuses.getOrDefault(m, PeriodStatus.OPEN)))
				.toList();

		Map<Long, BigDecimal> movementTotals = movements
				.sumByEntryOfPeriodRange(userId, range.from(), range.to()).stream()
				.collect(Collectors.toMap(EntryTotal::entryId, EntryTotal::total));
		Map<YearMonth, List<Built>> byMonth = new LinkedHashMap<>();
		monthList.forEach(m -> byMonth.put(m, new ArrayList<>()));
		for (BudgetEntry entry : entries.findByUserIdAndPeriodRangeWithDetails(userId, range.from(), range.to())) {
			Built built = new Built(PeriodViewService.row(entry, movementTotals.get(entry.getId()), today),
					entry.getBudgetItem() == null ? null : entry.getBudgetItem().getDefaultAccount().getCurrency());
			byMonth.get(entry.getPeriod().getPeriodMonth()).add(built);
		}

		return new Grid(range.from(), range.to(), current, start, horizon, months, rows(monthList, byMonth),
				totals(monthList, byMonth));
	}

	private static List<Row> rows(List<YearMonth> months, Map<YearMonth, List<Built>> byMonth) {
		// Clave de fila: el Concepto, o el origen, tipo y moneda de las partidas sin Concepto.
		Map<String, Map<YearMonth, List<Built>>> groups = new LinkedHashMap<>();
		Map<String, Built> first = new LinkedHashMap<>();
		for (YearMonth month : months) {
			for (Built built : byMonth.get(month)) {
				EntryRow row = built.row();
				String key = row.budgetItemId() != null ? "item:" + row.budgetItemId()
						: row.origin() + ":" + row.kind() + ":" + row.currency();
				first.putIfAbsent(key, built);
				groups.computeIfAbsent(key, k -> new LinkedHashMap<>())
						.computeIfAbsent(month, m -> new ArrayList<>()).add(built);
			}
		}
		List<Row> rows = new ArrayList<>();
		groups.forEach((key, cellsByMonth) -> {
			EntryRow head = first.get(key).row();
			boolean item = head.budgetItemId() != null;
			List<Cell> cells = months.stream().map(m -> cell(cellsByMonth.get(m))).toList();
			rows.add(new Row(head.budgetItemId(), item ? null : head.origin(), item ? head.name() : null, head.kind(),
					item ? first.get(key).itemCurrency() : head.currency(), cells));
		});
		rows.sort(ROW_ORDER);
		return rows;
	}

	private static Cell cell(List<Built> built) {
		if (built == null) {
			return null;
		}
		if (built.size() == 1) {
			EntryRow row = built.get(0).row();
			return new Cell(row.id(), 1, row.currency(), row.amounts().forecast(), row.amounts().status(),
					row.manual(), row.amounts().status() == EntryStatus.CONSOLIDATED);
		}
		BigDecimal forecast = built.stream().map(b -> b.row().amounts().forecast()).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		return new Cell(null, built.size(), built.get(0).row().currency(), forecast, null,
				built.stream().anyMatch(b -> b.row().manual()),
				built.stream().allMatch(b -> b.row().amounts().status() == EntryStatus.CONSOLIDATED));
	}

	private static List<MonthTotal> totals(List<YearMonth> months, Map<YearMonth, List<Built>> byMonth) {
		return months.stream()
				.map(month -> new MonthTotal(month, MonthTotals.calculate(byMonth.get(month).stream()
						.map(Built::row)
						.map(r -> new MonthTotals.Line(r.kind(), r.currency(), r.budgetedAmount(),
								r.amounts().actual(), r.amounts().pending(), r.amounts().forecast()))
						.toList())))
				.toList();
	}
}
