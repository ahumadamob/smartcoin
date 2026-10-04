package com.smartcoin.shared.error;

import java.util.List;

/** Error de negocio. El {@code detail} va en español y se muestra tal cual al usuario. */
public class BusinessException extends RuntimeException {

	private final ErrorCode code;
	private final List<Long> entries;

	public BusinessException(ErrorCode code, String detail) {
		this(code, detail, List.of());
	}

	/** Con los ids de las partidas que causan el error. */
	public BusinessException(ErrorCode code, String detail, List<Long> entries) {
		super(detail);
		this.code = code;
		this.entries = List.copyOf(entries);
	}

	public ErrorCode code() {
		return code;
	}

	public List<Long> entries() {
		return entries;
	}
}
