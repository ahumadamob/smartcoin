package com.smartcoin.period.domain;

/**
 * Por qué un saldo real recibido no corresponde (RN-39). La simulación lo informa y no lo aplica; el cierre responderá
 * {@code VALIDATION_ERROR} (D-44).
 */
public enum InvalidBalanceReason {
	/**
	 * La cuenta no se concilia en el período: se abrió después del último día, es de otro usuario o no existe. Los tres
	 * se informan igual (D-24).
	 */
	NOT_RECONCILABLE
}
