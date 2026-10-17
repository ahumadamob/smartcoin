package com.smartcoin.report.domain;

import static com.smartcoin.shared.domain.Currency.ARS;
import static com.smartcoin.shared.domain.Currency.USD;
import static com.smartcoin.shared.domain.EntryKind.EXPENSE;
import static com.smartcoin.shared.domain.EntryKind.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.report.domain.ProjectionCalculator.AccountStart;
import com.smartcoin.report.domain.ProjectionCalculator.Input;
import com.smartcoin.report.domain.ProjectionCalculator.Month;
import com.smartcoin.report.domain.ProjectionCalculator.PendingLine;
import com.smartcoin.report.domain.ProjectionCalculator.Result;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RN-46 (HU-35), con hoy el 17/10/2026 (Clock fijo en el servicio; acá se pasa la fecha) y el horizonte en 2028-10, que
 * da 25 meses.
 */
class ProjectionCalculatorTest {

	static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
	static final YearMonth HORIZON = YearMonth.of(2028, 10);

	static final AccountStart BANK = new AccountStart(1, "Banco", ARS, new BigDecimal("100000.00"));
	static final AccountStart WALLET = new AccountStart(2, "Billetera", ARS, new BigDecimal("20000.00"));
	static final AccountStart DOLLARS = new AccountStart(3, "Caja USD", USD, new BigDecimal("500.00"));

	static PendingLine pending(long accountId, EntryKind kind, String dueDate, String amount) {
		return new PendingLine(accountId, kind, LocalDate.parse(dueDate), new BigDecimal(amount));
	}

	static Result project(YearMonth until, List<AccountStart> accounts, PendingLine... pending) {
		return ProjectionCalculator.calculate(new Input(TODAY, until, accounts, List.of(pending)));
	}

	static String balance(Result result, String month, long accountId) {
		Month m = result.months().stream().filter(x -> x.month().equals(YearMonth.parse(month))).findFirst()
				.orElseThrow();
		return m.balances().stream().filter(b -> b.accountId() == accountId).findFirst().orElseThrow()
				.balance().toPlainString();
	}

	static String subtotal(Result result, String month, Currency currency) {
		Month m = result.months().stream().filter(x -> x.month().equals(YearMonth.parse(month))).findFirst()
				.orElseThrow();
		return m.subtotals().stream().filter(s -> s.currency() == currency).findFirst().orElseThrow()
				.balance().toPlainString();
	}

	@Test
	void anAccountWithoutPendingRepeatsItsBalanceInEveryMonth() {
		Result result = project(HORIZON, List.of(BANK));

		assertThat(result.months()).hasSize(25);
		assertThat(result.months().get(0).month()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(result.months().get(24).month()).isEqualTo(HORIZON);
		assertThat(result.months()).allSatisfy(m -> assertThat(m.balances().get(0).balance().toPlainString())
				.isEqualTo("100000.00"));
	}

	@Test
	void anIncomeAndAnExpenseInDifferentMonthsAccumulate() {
		Result result = project(HORIZON, List.of(BANK),
				pending(1, EXPENSE, "2026-11-20", "40000.00"),
				pending(1, INCOME, "2026-12-05", "300000.00"));

		assertThat(balance(result, "2026-10", 1)).isEqualTo("100000.00");
		assertThat(balance(result, "2026-11", 1)).isEqualTo("60000.00");
		assertThat(balance(result, "2026-12", 1)).isEqualTo("360000.00");
		assertThat(balance(result, "2027-01", 1)).isEqualTo("360000.00");
		assertThat(balance(result, "2028-10", 1)).isEqualTo("360000.00");
	}

	@Test
	void anOverduePendingCountsFromTheCurrentMonth() {
		Result result = project(YearMonth.of(2026, 12), List.of(BANK),
				pending(1, EXPENSE, "2026-08-10", "20000.00"));

		assertThat(balance(result, "2026-10", 1)).isEqualTo("80000.00");
		assertThat(balance(result, "2026-12", 1)).isEqualTo("80000.00");
	}

	@Test
	void aPendingDueEarlierInTheCurrentMonthThanTodayAlsoCountsInIt() {
		Result result = project(YearMonth.of(2026, 11), List.of(BANK),
				pending(1, EXPENSE, "2026-10-01", "10000.00"));

		assertThat(balance(result, "2026-10", 1)).isEqualTo("90000.00");
	}

	@Test
	void aPartialEntryCountsOnlyWhatIsMissing() {
		// El pendiente que llega ya es el de RN-17: 40.000 presupuestados menos 15.000 pagados.
		Result result = project(YearMonth.of(2026, 11), List.of(BANK),
				pending(1, EXPENSE, "2026-10-20", "25000.00"));

		assertThat(balance(result, "2026-10", 1)).isEqualTo("75000.00");
	}

	@Test
	void aFullyPaidEntryWithZeroPendingDoesNotCount() {
		Result result = project(YearMonth.of(2026, 11), List.of(BANK),
				pending(1, EXPENSE, "2026-10-20", "0.00"));

		assertThat(balance(result, "2026-11", 1)).isEqualTo("100000.00");
	}

	@Test
	void thePendingIsProjectedInTheAccountPlannedForTheEntryEvenIfThePaymentCameFromAnother() {
		// La Billetera ya pagó 15.000 de una cuenta de 20.000: su saldo actual es 5.000. El pendiente sigue en el Banco.
		AccountStart walletAfterPaying = new AccountStart(2, "Billetera", ARS, new BigDecimal("5000.00"));
		Result result = project(YearMonth.of(2026, 11), List.of(BANK, walletAfterPaying),
				pending(1, EXPENSE, "2026-10-20", "25000.00"));

		assertThat(balance(result, "2026-10", 1)).isEqualTo("75000.00");
		assertThat(balance(result, "2026-10", 2)).isEqualTo("5000.00");
		assertThat(subtotal(result, "2026-10", ARS)).isEqualTo("80000.00");
	}

	@Test
	void subtotalsAreSeparatedByCurrency() {
		Result result = project(YearMonth.of(2026, 11), List.of(BANK, WALLET, DOLLARS),
				pending(1, INCOME, "2026-10-30", "300000.00"),
				pending(2, EXPENSE, "2026-11-10", "5000.00"),
				pending(3, EXPENSE, "2026-11-15", "50.00"));

		assertThat(subtotal(result, "2026-10", ARS)).isEqualTo("420000.00");
		assertThat(subtotal(result, "2026-10", USD)).isEqualTo("500.00");
		assertThat(subtotal(result, "2026-11", ARS)).isEqualTo("415000.00");
		assertThat(subtotal(result, "2026-11", USD)).isEqualTo("450.00");
		assertThat(result.months().get(0).subtotals()).extracting(s -> s.currency()).containsExactly(ARS, USD);
	}

	@Test
	void aCurrencyWithoutAccountsHasNoSubtotal() {
		Result result = project(YearMonth.of(2026, 10), List.of(DOLLARS));

		assertThat(result.months().get(0).subtotals()).extracting(s -> s.currency()).containsExactly(USD);
	}

	@ParameterizedTest
	@CsvSource({
			"2028-10-31, 2028-10",  // el último día del horizonte entra en el último mes
			"2028-10-01, 2028-10",
			"2026-10-31, 2026-10",  // el último día del mes actual entra en el mes actual
			"2026-11-01, 2026-11"   // el primer día del mes siguiente, recién en el siguiente
	})
	void aPendingCountsFromTheMonthOfItsEffectiveDate(LocalDate dueDate, YearMonth firstMonth) {
		Result result = ProjectionCalculator.calculate(new Input(TODAY, HORIZON, List.of(BANK),
				List.of(new PendingLine(1, EXPENSE, dueDate, new BigDecimal("1000.00")))));

		for (Month month : result.months()) {
			String expected = month.month().isBefore(firstMonth) ? "100000.00" : "99000.00";
			assertThat(month.balances().get(0).balance().toPlainString()).as(month.month().toString())
					.isEqualTo(expected);
		}
	}

	@Test
	void theLastMonthIsTheHorizonAndAPendingAfterItIsIgnored() {
		Result result = project(HORIZON, List.of(BANK),
				pending(1, EXPENSE, "2028-11-01", "1000.00"));

		assertThat(result.until()).isEqualTo(HORIZON);
		assertThat(result.months().get(result.months().size() - 1).month()).isEqualTo(HORIZON);
		assertThat(balance(result, "2028-10", 1)).isEqualTo("100000.00");
	}

	@Test
	void untilTheCurrentMonthGivesASingleMonth() {
		Result result = project(YearMonth.of(2026, 10), List.of(BANK));

		assertThat(result.months()).hasSize(1);
	}

	@Test
	void aPendingOfAnUnknownAccountIsIgnored() {
		Result result = project(YearMonth.of(2026, 10), List.of(BANK),
				pending(99, EXPENSE, "2026-10-20", "1000.00"));

		assertThat(balance(result, "2026-10", 1)).isEqualTo("100000.00");
	}

	@Test
	void anUntilBeforeTheCurrentMonthIsRejected() {
		assertThatThrownBy(() -> project(YearMonth.of(2026, 9), List.of(BANK)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aNegativeProjectedBalanceIsKeptWithItsSign() {
		Result result = project(YearMonth.of(2026, 11), List.of(BANK),
				pending(1, EXPENSE, "2026-11-05", "150000.00"));

		assertThat(balance(result, "2026-11", 1)).isEqualTo("-50000.00");
		assertThat(subtotal(result, "2026-11", ARS)).isEqualTo("-50000.00");
	}
}
