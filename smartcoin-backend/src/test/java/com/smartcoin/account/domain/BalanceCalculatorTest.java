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
}
