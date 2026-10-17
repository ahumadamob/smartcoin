package com.smartcoin.period.domain;

import com.smartcoin.shared.error.ErrorCode;

/** Qué precondición de RN-38 impide cerrar un período, en el orden en que RN-38 las enumera. */
public enum ClosingBlockReason {
	PERIOD_CLOSED(ErrorCode.PERIOD_CLOSED),
	PREVIOUS_PERIOD_OPEN(ErrorCode.PREVIOUS_PERIOD_OPEN),
	PERIOD_NOT_FINISHED(ErrorCode.PERIOD_NOT_FINISHED);

	private final ErrorCode code;

	ClosingBlockReason(ErrorCode code) {
		this.code = code;
	}

	/** El código de error que responderá el cierre. */
	public ErrorCode code() {
		return code;
	}
}
