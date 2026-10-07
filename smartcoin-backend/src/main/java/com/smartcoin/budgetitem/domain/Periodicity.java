package com.smartcoin.budgetitem.domain;

/** Periodicidad de un Concepto, con su paso en meses (RN-11). */
public enum Periodicity {
	MONTHLY(1),
	BIMONTHLY(2),
	QUARTERLY(3),
	SEMIANNUAL(6),
	ANNUAL(12);

	private final int months;

	Periodicity(int months) {
		this.months = months;
	}

	/** Meses entre una partida y la siguiente. */
	public int months() {
		return months;
	}
}
