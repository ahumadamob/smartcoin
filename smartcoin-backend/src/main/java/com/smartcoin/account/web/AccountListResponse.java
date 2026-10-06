package com.smartcoin.account.web;

import java.math.BigDecimal;
import java.util.List;

import com.smartcoin.account.service.AccountService.AccountList;
import com.smartcoin.shared.domain.Currency;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Las cuentas del usuario con su saldo actual y un subtotal por moneda.")
public record AccountListResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Ordenadas por moneda y nombre.")
		List<AccountResponse> accounts,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Un subtotal por cada moneda que tiene cuentas, en el orden ARS, USD. Nunca hay un total "
						+ "que mezcle monedas (RN-04).")
		List<CurrencySubtotal> subtotals) {

	@Schema(name = "CurrencySubtotal", description = "Suma de los saldos actuales de las cuentas de una moneda.")
	public record CurrencySubtotal(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currency currency,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "350000.00") BigDecimal balance) {
	}

	static AccountListResponse from(AccountList list) {
		return new AccountListResponse(list.accounts().stream().map(AccountResponse::from).toList(),
				list.subtotals().stream().map(s -> new CurrencySubtotal(s.currency(), s.balance())).toList());
	}
}
