package com.smartcoin.entry.domain;

/** Qué hacer con las partidas editadas del destino al propagar (RN-26). */
public enum ManualEntriesPolicy {
	/** Respetar: no cambian y siguen editadas. */
	KEEP,
	/** Pisar: toman la estimación y dejan de estar editadas. */
	OVERWRITE
}
