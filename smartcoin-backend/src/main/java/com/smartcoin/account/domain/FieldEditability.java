package com.smartcoin.account.domain;

/**
 * Si un campo se puede editar y, si no, por qué.
 *
 * @param reason motivo en español; {@code null} cuando se puede editar
 */
public record FieldEditability(boolean editable, String reason) {

	static FieldEditability yes() {
		return new FieldEditability(true, null);
	}

	static FieldEditability no(String reason) {
		return new FieldEditability(false, reason);
	}
}
