package com.smartcoin.entry.domain;

/** Estado de la partida hacia afuera (RN-16). En la base solo se guarda {@link StoredEntryStatus}. */
public enum EntryStatus {
	ESTIMATED,
	PARTIAL,
	CONSOLIDATED
}
