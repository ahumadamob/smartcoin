package com.smartcoin.entry.domain;

import com.smartcoin.shared.error.ErrorCode;

/** Qué impide consolidar una partida (RN-25), en el orden en que se evalúa (D-36). */
public enum ConsolidationBlockReason {
	PERIOD_CLOSED(ErrorCode.PERIOD_CLOSED),
	ENTRY_NOT_PENDING(ErrorCode.ENTRY_NOT_PENDING),
	CONSOLIDATION_REQUIRES_MOVEMENT(ErrorCode.CONSOLIDATION_REQUIRES_MOVEMENT);

	private final ErrorCode code;

	ConsolidationBlockReason(ErrorCode code) {
		this.code = code;
	}

	/** El código de error que responde la consolidación. */
	public ErrorCode code() {
		return code;
	}
}
