package com.smartcoin.entry.domain;

/**
 * Alcance al eliminar la partida de un Concepto (RN-31, D-19). Una partida sin Concepto no tiene alcance.
 */
public enum DeletionScope {

	/** Solo la partida elegida: el Concepto sigue y la partida no reaparece (RN-13). */
	ONLY_THIS,

	/** La partida elegida y todas las posteriores del Concepto: el Concepto termina el mes anterior. */
	THIS_AND_FUTURE
}
