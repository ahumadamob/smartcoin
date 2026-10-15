package com.smartcoin.transfer.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import com.smartcoin.shared.domain.Currency;

/**
 * Tipo de cambio de una transferencia (RN-37, D-40). Regla pura: no sabe de Spring ni de la base. Es el monto en ARS
 * dividido por el monto en USD, sin importar cuál es el origen, redondeado a 2 decimales con {@code HALF_UP}. Solo
 * existe entre cuentas de distinta moneda; no se guarda.
 */
public final class ExchangeRate {

	private ExchangeRate() {
	}

	/** El tipo de cambio, o vacío si las dos monedas son iguales. Los montos son mayores que 0 (RN-36). */
	public static Optional<BigDecimal> of(Currency sourceCurrency, BigDecimal sourceAmount, Currency targetCurrency,
			BigDecimal targetAmount) {
		if (sourceCurrency == targetCurrency) {
			return Optional.empty();
		}
		BigDecimal ars = sourceCurrency == Currency.ARS ? sourceAmount : targetAmount;
		BigDecimal usd = sourceCurrency == Currency.USD ? sourceAmount : targetAmount;
		return Optional.of(ars.divide(usd, 2, RoundingMode.HALF_UP));
	}
}
