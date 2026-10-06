package com.smartcoin.account.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

/**
 * Qué campos de una cuenta se pueden editar (RN-33). Nombre y tipo se editan siempre, por eso no figuran.
 *
 * @param latestOpeningDate fecha más tardía que puede tener la apertura (la de su primer movimiento o transferencia),
 *                          o {@code null} si no hay tope
 */
public record AccountEditability(FieldEditability currency, FieldEditability initialBalance,
		FieldEditability openingDate, LocalDate latestOpeningDate) {

	private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	static final String CURRENCY_REASON = "La moneda no se puede cambiar: la cuenta ya está usada en Conceptos, "
			+ "partidas, movimientos, transferencias o cierres.";
	static final String INITIAL_BALANCE_REASON = "El saldo inicial no se puede cambiar: la cuenta ya tiene cierres de mes.";
	static final String OPENING_DATE_REASON = "La fecha de apertura no se puede cambiar: la cuenta ya tiene cierres de mes.";

	public static AccountEditability of(AccountUsage usage) {
		return new AccountEditability(
				usage.referenced() ? FieldEditability.no(CURRENCY_REASON) : FieldEditability.yes(),
				usage.hasClosings() ? FieldEditability.no(INITIAL_BALANCE_REASON) : FieldEditability.yes(),
				usage.hasClosings() ? FieldEditability.no(OPENING_DATE_REASON) : FieldEditability.yes(),
				usage.firstActivityDate());
	}

	/**
	 * Verifica que el cambio pedido solo toque lo editable. Enviar el mismo valor que ya tiene no es un cambio; los
	 * montos se comparan con {@code compareTo}.
	 *
	 * @throws BusinessException {@code FIELD_NOT_EDITABLE} con el motivo
	 */
	public void verifyChange(AccountValues current, AccountValues requested) {
		if (current.currency() != requested.currency() && !currency.editable()) {
			throw notEditable(currency.reason());
		}
		if (current.initialBalance().compareTo(requested.initialBalance()) != 0 && !initialBalance.editable()) {
			throw notEditable(initialBalance.reason());
		}
		if (!current.openingDate().equals(requested.openingDate())) {
			if (!openingDate.editable()) {
				throw notEditable(openingDate.reason());
			}
			if (latestOpeningDate != null && requested.openingDate().isAfter(latestOpeningDate)) {
				throw notEditable("La fecha de apertura no puede ser posterior al " + DISPLAY.format(latestOpeningDate)
						+ ", fecha del primer movimiento o transferencia de la cuenta.");
			}
		}
	}

	private static BusinessException notEditable(String reason) {
		return new BusinessException(ErrorCode.FIELD_NOT_EDITABLE, reason);
	}
}
