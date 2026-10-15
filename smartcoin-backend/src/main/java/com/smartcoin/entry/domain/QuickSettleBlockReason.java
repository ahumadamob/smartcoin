package com.smartcoin.entry.domain;

import com.smartcoin.shared.error.ErrorCode;

/** Qué impide el pago rápido de una partida (RN-24), en el orden en que se evalúa (D-37). */
public enum QuickSettleBlockReason {
	PERIOD_CLOSED(ErrorCode.PERIOD_CLOSED),
	ENTRY_NOT_PENDING(ErrorCode.ENTRY_NOT_PENDING),
	NOTHING_PENDING(ErrorCode.NOTHING_PENDING);

	private final ErrorCode code;

	QuickSettleBlockReason(ErrorCode code) {
		this.code = code;
	}

	/** El código de error que responde el pago rápido. */
	public ErrorCode code() {
		return code;
	}
}
