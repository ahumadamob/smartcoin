package com.smartcoin.period.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

/**
 * Totales de la vista del mes (RN-44): por moneda, para ingresos y para gastos, presupuestado, real, pendiente y
 * estimado; y el resultado de cada moneda. Montos de distinta moneda nunca se suman (RN-04). Regla pura.
 */
public final class MonthTotals {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	/** Una partida del período, con sus valores derivados ya calculados. */
	public record Line(EntryKind kind, Currency currency, BigDecimal budgeted, BigDecimal actual, BigDecimal pending,
			BigDecimal forecast) {
	}

	/**
	 * Totales de los ingresos o de los gastos de una moneda.
	 *
	 * @param entryCount cuántas partidas suma; con 0, todos los montos son 0
	 */
	public record SideTotals(int entryCount, BigDecimal budgeted, BigDecimal actual, BigDecimal pending,
			BigDecimal forecast) {

		static final SideTotals EMPTY = new SideTotals(0, ZERO, ZERO, ZERO, ZERO);

		SideTotals plus(Line line) {
			return new SideTotals(entryCount + 1, budgeted.add(line.budgeted()), actual.add(line.actual()),
					pending.add(line.pending()), forecast.add(line.forecast()));
		}
	}

	/** @param result estimado de ingresos menos estimado de gastos; puede ser negativo */
	public record CurrencyTotals(Currency currency, SideTotals income, SideTotals expense, BigDecimal result) {
	}

	private MonthTotals() {
	}

	/**
	 * Un total por cada moneda que tiene al menos una partida, en el orden de {@link Currency} (ARS, USD). Si una
	 * moneda solo tiene ingresos o solo gastos, el otro lado va en 0. Sin partidas, la lista es vacía.
	 */
	public static List<CurrencyTotals> calculate(List<Line> lines) {
		List<CurrencyTotals> totals = new ArrayList<>();
		for (Currency currency : Currency.values()) {
			SideTotals income = SideTotals.EMPTY;
			SideTotals expense = SideTotals.EMPTY;
			for (Line line : lines) {
				if (line.currency() != currency) {
					continue;
				}
				if (line.kind() == EntryKind.INCOME) {
					income = income.plus(line);
				}
				else {
					expense = expense.plus(line);
				}
			}
			if (income.entryCount() + expense.entryCount() > 0) {
				totals.add(new CurrencyTotals(currency, income, expense,
						income.forecast().subtract(expense.forecast())));
			}
		}
		return totals;
	}
}
