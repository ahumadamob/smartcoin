package com.smartcoin.period.domain;

/**
 * Por qué una resolución recibida no corresponde (RN-39). La simulación la informa y no la aplica; el cierre
 * responderá {@code VALIDATION_ERROR} (D-43).
 */
public enum InvalidResolutionReason {
	/** La partida no es del período: es de otro mes, de otro usuario o no existe. Los tres se informan igual (D-24). */
	NOT_IN_PERIOD,
	/** La partida es del período pero ya está consolidada. */
	NOT_PENDING
}
