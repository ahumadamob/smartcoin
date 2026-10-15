package com.smartcoin.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.smartcoin.shared.domain.Currency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** RN-37: tipo de cambio = ARS / USD, 2 decimales, HALF_UP, sin importar el origen. Regla pura, sin Spring. */
class ExchangeRateTest {

	@ParameterizedTest(name = "{0} {1} -> {2} {3} = {4}")
	@CsvSource({
			// Ejemplos de RN-37: compra (ARS -> USD) y venta (USD -> ARS).
			"ARS, 1250000.00, USD, 1000.00, 1250.00",
			"USD, 500.00, ARS, 610000.00, 1220.00",
			// Redondeo hacia arriba y hacia abajo.
			"ARS, 100000.00, USD, 83.00, 1204.82",
			"ARS, 100000.00, USD, 81.00, 1234.57",
			"ARS, 100000.00, USD, 3.00, 33333.33",
			"ARS, 200000.00, USD, 3.00, 66666.67",
			// Termina exactamente en medio centavo: HALF_UP sube. 1,005 -> 1,01 y 1,015 -> 1,02.
			"ARS, 100.50, USD, 100.00, 1.01",
			"ARS, 101.50, USD, 100.00, 1.02",
			"USD, 100.00, ARS, 100.50, 1.01",
			// El medio centavo que queda debajo del corte no sube: 1,004 -> 1,00.
			"ARS, 100.40, USD, 100.00, 1.00",
			// USD chicos.
			"ARS, 12.50, USD, 0.01, 1250.00",
			"ARS, 0.01, USD, 0.01, 1.00",
			"ARS, 0.01, USD, 1000.00, 0.00",
			"USD, 0.01, ARS, 1250.00, 125000.00"
	})
	void dividesArsByUsdRoundedHalfUp(Currency sourceCurrency, String sourceAmount, Currency targetCurrency,
			String targetAmount, String expected) {
		BigDecimal rate = ExchangeRate.of(sourceCurrency, new BigDecimal(sourceAmount), targetCurrency,
				new BigDecimal(targetAmount)).orElseThrow();

		assertThat(rate).isEqualTo(new BigDecimal(expected));
		assertThat(rate.scale()).isEqualTo(2);
	}

	@ParameterizedTest
	@EnumSource(Currency.class)
	void thereIsNoRateBetweenAccountsOfTheSameCurrency(Currency currency) {
		assertThat(ExchangeRate.of(currency, new BigDecimal("10.00"), currency, new BigDecimal("10.00"))).isEmpty();
	}

	@Test
	void theRateDoesNotDependOnWhichAccountIsTheSource() {
		BigDecimal purchase = ExchangeRate.of(Currency.ARS, new BigDecimal("1250000.00"), Currency.USD,
				new BigDecimal("1000.00")).orElseThrow();
		BigDecimal reversed = ExchangeRate.of(Currency.USD, new BigDecimal("1000.00"), Currency.ARS,
				new BigDecimal("1250000.00")).orElseThrow();

		assertThat(purchase).isEqualTo(reversed);
	}
}
