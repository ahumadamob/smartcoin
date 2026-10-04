package com.smartcoin.shared.security;

import java.time.Instant;

/** Token recién emitido y el instante en que vence. */
public record IssuedToken(String value, Instant expiresAt) {

	/** Sin el token: el {@code toString} de un record lo imprimiría y podría terminar en un log. */
	@Override
	public String toString() {
		return "IssuedToken[expiresAt=" + expiresAt + "]";
	}
}
