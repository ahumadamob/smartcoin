package com.smartcoin.account.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Saldo de una cuenta (RN-35): saldo inicial, más los movimientos de partidas de ingreso, menos los de partidas de
 * gasto, más el monto de destino de las transferencias entrantes, menos el monto de origen de las salientes.
 *
 * <p>Regla pura: recibe los totales ya acotados a la fecha pedida (todo con fecha menor o igual al día de corte; cuenta
 * la fecha del movimiento, no el período de su partida) y no redondea. Los montos tienen escala 2.
 */
public final class BalanceCalculator {

	private BalanceCalculator() {
	}

	/** Totales de una cuenta con fecha menor o igual al día de corte. Un total ausente es cero. */
	public record Totals(BigDecimal incomeMovements, BigDecimal expenseMovements, BigDecimal incomingTransfers,
			BigDecimal outgoingTransfers) {

		public static Totals none() {
			return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		}
	}

	public static BigDecimal balance(BigDecimal initialBalance, Totals totals) {
		Objects.requireNonNull(initialBalance, "initialBalance");
		Objects.requireNonNull(totals, "totals");
		return initialBalance
				.add(totals.incomeMovements())
				.subtract(totals.expenseMovements())
				.add(totals.incomingTransfers())
				.subtract(totals.outgoingTransfers())
				.setScale(2);
	}
}
