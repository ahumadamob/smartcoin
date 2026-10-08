package com.smartcoin.entry.domain;

import java.math.BigDecimal;

/**
 * Valores derivados de una partida (RN-16, RN-17): no se guardan, se calculan. Regla pura: recibe valores y devuelve
 * resultados.
 *
 * @param actual   real: la suma de sus movimientos
 * @param pending  pendiente: 0 si está consolidada; si no, lo que falta para llegar al presupuestado, nunca negativo
 * @param forecast estimado: el monto consolidado si está consolidada; si no, real más pendiente
 * @param status   estado mostrado
 */
public record EntryAmounts(BigDecimal actual, BigDecimal pending, BigDecimal forecast, EntryStatus status) {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	/**
	 * @param budgeted           presupuestado
	 * @param stored             estado guardado
	 * @param consolidatedAmount monto consolidado; solo tiene valor en una consolidada
	 * @param movementsTotal     suma de los movimientos de la partida, o {@code null} si no tiene ninguno. Como todo
	 *                           movimiento es mayor que 0 (RN-03), una suma en 0 también es «sin movimientos».
	 */
	public static EntryAmounts of(BigDecimal budgeted, StoredEntryStatus stored, BigDecimal consolidatedAmount,
			BigDecimal movementsTotal) {
		BigDecimal actual = movementsTotal == null ? ZERO : movementsTotal;
		if (stored == StoredEntryStatus.CONSOLIDATED) {
			return new EntryAmounts(actual, ZERO, consolidatedAmount, EntryStatus.CONSOLIDATED);
		}
		BigDecimal pending = budgeted.subtract(actual).max(ZERO);
		EntryStatus status = actual.signum() > 0 ? EntryStatus.PARTIAL : EntryStatus.ESTIMATED;
		return new EntryAmounts(actual, pending, actual.add(pending), status);
	}
}
