package com.smartcoin.account.domain;

import java.math.BigDecimal;

import com.smartcoin.account.domain.BalanceCalculator.Totals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HU-08, RN-35: sin Spring ni base de datos. */
class BalanceCalculatorTest {

	private static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', useHeadersInDisplayName = false, textBlock = """
			ejemplo de la historia            | 100000.00 | 50000.00 | 20000.00 | 0.00     | 30000.00 | 100000.00
			solo saldo inicial                | 250000.00 | 0.00     | 0.00     | 0.00     | 0.00     | 250000.00
			saldo inicial negativo            | -1500.50  | 0.00     | 0.00     | 0.00     | 0.00     | -1500.50
			saldo inicial negativo con cobro  | -1500.50  | 2000.00  | 0.00     | 0.00     | 0.00     | 499.50
			los gastos pueden dejarlo negativo| 1000.00   | 0.00     | 2500.25  | 0.00     | 0.00     | -1500.25
			transferencia entrante (USD)      | 100.00    | 0.00     | 0.00     | 1000.00  | 0.00     | 1100.00
			sueldo cobrado por adelantado     | 10000.00  | 800000.00| 0.00     | 0.00     | 0.00     | 810000.00
			sin redondeos                     | 0.01      | 0.02     | 0.01     | 0.00     | 0.00     | 0.02
			""")
	void balanceFollowsRn35(String name, String initial, String income, String expense, String incoming,
			String outgoing, String expected) {
		BigDecimal result = BalanceCalculator.balance(d(initial.strip()),
				new Totals(d(income.strip()), d(expense.strip()), d(incoming.strip()), d(outgoing.strip())));

		assertThat(result).isEqualTo(d(expected.strip()));
		assertThat(result.scale()).isEqualTo(2);
	}

	@Test
	void anAccountWithoutActivityKeepsItsInitialBalance() {
		assertThat(BalanceCalculator.balance(d("100000.00"), Totals.none())).isEqualTo(d("100000.00"));
	}

	@Test
	void doesNotRoundValuesWithMoreThanTwoDecimals() {
		assertThatThrownBy(() -> BalanceCalculator.balance(d("1.005"), Totals.none()))
				.isInstanceOf(ArithmeticException.class);
	}

	/** HU-27, criterio 5: extraer 50.000,00 del banco al efectivo resta en uno, suma en el otro y el total no cambia. */
	@Test
	void aTransferBetweenAccountsOfTheSameCurrencyLeavesTheSubtotalUnchanged() {
		BigDecimal bankBefore = BalanceCalculator.balance(d("100000.00"), Totals.none());
		BigDecimal cashBefore = BalanceCalculator.balance(d("20000.00"), Totals.none());

		BigDecimal bankAfter = BalanceCalculator.balance(d("100000.00"),
				new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, d("50000.00")));
		BigDecimal cashAfter = BalanceCalculator.balance(d("20000.00"),
				new Totals(BigDecimal.ZERO, BigDecimal.ZERO, d("50000.00"), BigDecimal.ZERO));

		assertThat(bankAfter).isEqualTo(d("50000.00"));
		assertThat(cashAfter).isEqualTo(d("70000.00"));
		assertThat(bankAfter.add(cashAfter)).isEqualTo(bankBefore.add(cashBefore));
	}

	/** RN-35: cada lado usa su propio monto, así que una compra de dólares resta pesos y suma dólares. */
	@Test
	void aTransferBetweenDifferentCurrenciesUsesEachSidesOwnAmount() {
		BigDecimal pesos = BalanceCalculator.balance(d("2000000.00"),
				new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, d("1250000.00")));
		BigDecimal dollars = BalanceCalculator.balance(d("0.00"),
				new Totals(BigDecimal.ZERO, BigDecimal.ZERO, d("1000.00"), BigDecimal.ZERO));

		assertThat(pesos).isEqualTo(d("750000.00"));
		assertThat(dollars).isEqualTo(d("1000.00"));
	}
}
