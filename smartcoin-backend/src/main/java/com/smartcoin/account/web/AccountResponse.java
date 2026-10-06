package com.smartcoin.account.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.smartcoin.account.domain.AccountEditability;
import com.smartcoin.account.domain.AccountType;
import com.smartcoin.account.domain.FieldEditability;
import com.smartcoin.account.service.AccountService.AccountView;
import com.smartcoin.shared.domain.Currency;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Una cuenta del usuario, con qué campos se pueden editar.")
public record AccountResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
		Long id,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
		String name,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		AccountType type,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		Currency currency,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-01")
		LocalDate openingDate,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "150000.00")
		BigDecimal initialBalance,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Edición de los campos que no son siempre editables. El nombre y el tipo siempre se pueden editar.")
		Editability editability) {

	@Schema(name = "AccountEditability", description = "Qué campos condicionados (RN-33) se pueden editar hoy.")
	public record Editability(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) FieldState currency,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) FieldState initialBalance,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) FieldState openingDate) {
	}

	@Schema(name = "FieldEditability", description = "Si un campo se puede editar y, si no, el motivo.")
	public record FieldState(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean editable,
			@Schema(nullable = true, description = "Motivo en español, solo cuando no es editable.",
					example = "La moneda no se puede cambiar: la cuenta ya está usada en Conceptos, partidas, movimientos, transferencias o cierres.")
			String reason) {

		static FieldState from(FieldEditability field) {
			return new FieldState(field.editable(), field.reason());
		}
	}

	static AccountResponse from(AccountView view) {
		AccountEditability e = view.editability();
		return new AccountResponse(view.account().getId(), view.account().getName(), view.account().getType(),
				view.account().getCurrency(), view.account().getOpeningDate(), view.account().getInitialBalance(),
				new Editability(FieldState.from(e.currency()), FieldState.from(e.initialBalance()),
						FieldState.from(e.openingDate())));
	}
}
