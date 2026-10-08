package com.smartcoin.period.domain;

import static com.smartcoin.shared.domain.Currency.ARS;
import static com.smartcoin.shared.domain.Currency.USD;
import static com.smartcoin.shared.domain.EntryKind.EXPENSE;
import static com.smartcoin.shared.domain.EntryKind.INCOME;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.period.domain.MonthTotals.CurrencyTotals;
import com.smartcoin.period.domain.MonthTotals.Line;
import com.smartcoin.period.domain.MonthTotals.SideTotals;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import org.junit.jupiter.api.Test;

/** RN-44 y RN-04: totales por moneda y resultado. */
class MonthTotalsTest {

	private static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	/** Una partida pendiente, con sus valores derivados calculados por la regla de RN-17. */
	private static Line pending(EntryKind kind, Currency currency, String budgeted, String movements) {
		return line(kind, currency, budgeted,
				EntryAmounts.of(amount(budgeted), StoredEntryStatus.PENDING, null,
						movements == null ? null : amount(movements)));
	}

	private static Line consolidated(EntryKind kind, Currency currency, String budgeted, String real) {
		return line(kind, currency, budgeted,
				EntryAmounts.of(amount(budgeted), StoredEntryStatus.CONSOLIDATED, amount(real), amount(real)));
	}

	private static Line line(EntryKind kind, Currency currency, String budgeted, EntryAmounts amounts) {
		return new Line(kind, currency, amount(budgeted), amounts.actual(), amounts.pending(), amounts.forecast());
	}

	private static SideTotals side(int count, String budgeted, String actual, String pending, String forecast) {
		return new SideTotals(count, amount(budgeted), amount(actual), amount(pending), amount(forecast));
	}

	@Test
	void theExampleOfTheStoryForNovember2026() {
		List<CurrencyTotals> totals = MonthTotals.calculate(List.of(
				consolidated(INCOME, ARS, "1200000.00", "1200000.00"), // Sueldo A
				pending(INCOME, ARS, "650000.00", null), // Sueldo B
				consolidated(EXPENSE, ARS, "450000.00", "450000.00"), // Alquiler
				pending(EXPENSE, ARS, "45000.00", "20000.00"), // Luz
				pending(EXPENSE, ARS, "240000.00", null))); // Resumen Visa

		assertThat(totals).containsExactly(new CurrencyTotals(ARS,
				side(2, "1850000.00", "1200000.00", "650000.00", "1850000.00"),
				side(3, "735000.00", "470000.00", "265000.00", "735000.00"),
				amount("1115000.00")));
	}

	@Test
	void pesosAndDollarsAreNeverAddedTogether() {
		List<CurrencyTotals> totals = MonthTotals.calculate(List.of(
				pending(INCOME, USD, "1000.00", "400.00"),
				pending(INCOME, ARS, "650000.00", null),
				pending(EXPENSE, USD, "250.50", null),
				pending(EXPENSE, ARS, "45000.00", "50000.00"),
				pending(INCOME, USD, "500.00", null)));

		assertThat(totals).containsExactly(
				new CurrencyTotals(ARS,
						side(1, "650000.00", "0.00", "650000.00", "650000.00"),
						side(1, "45000.00", "50000.00", "0.00", "50000.00"),
						amount("600000.00")),
				new CurrencyTotals(USD,
						side(2, "1500.00", "400.00", "1100.00", "1500.00"),
						side(1, "250.50", "0.00", "250.50", "250.50"),
						amount("1249.50")));
	}

	@Test
	void aCurrencyWithOnlyOneSideHasTheOtherInZero() {
		List<CurrencyTotals> totals = MonthTotals.calculate(List.of(
				pending(EXPENSE, ARS, "45000.00", null),
				pending(INCOME, USD, "1000.00", null)));

		assertThat(totals).containsExactly(
				new CurrencyTotals(ARS, side(0, "0.00", "0.00", "0.00", "0.00"),
						side(1, "45000.00", "0.00", "45000.00", "45000.00"), amount("-45000.00")),
				new CurrencyTotals(USD, side(1, "1000.00", "0.00", "1000.00", "1000.00"),
						side(0, "0.00", "0.00", "0.00", "0.00"), amount("1000.00")));
	}

	@Test
	void aCurrencyWithoutEntriesIsNotListed() {
		assertThat(MonthTotals.calculate(List.of(pending(EXPENSE, USD, "10.00", null))))
				.extracting(CurrencyTotals::currency).containsExactly(USD);
	}

	@Test
	void anEmptyPeriodHasNoTotals() {
		assertThat(MonthTotals.calculate(List.of())).isEmpty();
	}

	@Test
	void entriesBudgetedInZeroCountAndTheResultIsZeroWithTwoDecimals() {
		List<CurrencyTotals> totals = MonthTotals.calculate(List.of(pending(EXPENSE, ARS, "0.00", null)));

		assertThat(totals).containsExactly(new CurrencyTotals(ARS, side(0, "0.00", "0.00", "0.00", "0.00"),
				side(1, "0.00", "0.00", "0.00", "0.00"), amount("0.00")));
	}
}
