package com.smartcoin.shared.error;

import java.util.List;

/** Error de negocio. El {@code detail} va en español y se muestra tal cual al usuario. */
public class BusinessException extends RuntimeException {

	private final ErrorCode code;
	private final List<Long> entries;
	private final List<Long> accounts;
	private final String field;

	public BusinessException(ErrorCode code, String detail) {
		this(code, detail, List.of());
	}

	/** Con los ids de las partidas que causan el error. */
	public BusinessException(ErrorCode code, String detail, List<Long> entries) {
		this(code, detail, entries, List.of(), null);
	}

	private BusinessException(ErrorCode code, String detail, List<Long> entries, List<Long> accounts, String field) {
		super(detail);
		this.code = code;
		this.entries = List.copyOf(entries);
		this.accounts = List.copyOf(accounts);
		this.field = field;
	}

	/** Con los ids de las cuentas que causan el error (HU-33: {@code MISSING_REAL_BALANCE}). */
	public static BusinessException withAccounts(ErrorCode code, String detail, List<Long> accounts) {
		return new BusinessException(code, detail, List.of(), accounts, null);
	}

	/**
	 * {@code VALIDATION_ERROR} de un campo del cuerpo que solo se puede validar con datos (por ejemplo, una referencia
	 * a otro recurso). Sale con {@code errors} por campo, igual que los de Bean Validation.
	 */
	public static BusinessException invalidField(String field, String detail) {
		return new BusinessException(ErrorCode.VALIDATION_ERROR, detail, List.of(), List.of(), field);
	}

	public ErrorCode code() {
		return code;
	}

	public List<Long> entries() {
		return entries;
	}

	public List<Long> accounts() {
		return accounts;
	}

	/** Campo del cuerpo al que se refiere el error, o {@code null}. */
	public String field() {
		return field;
	}
}
