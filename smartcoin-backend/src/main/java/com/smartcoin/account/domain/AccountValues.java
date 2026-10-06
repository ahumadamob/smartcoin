package com.smartcoin.account.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.smartcoin.shared.domain.Currency;

/** Los datos editables de una cuenta, sin JPA: lo que tiene hoy y lo que se pide. */
public record AccountValues(String name, AccountType type, Currency currency, LocalDate openingDate,
		BigDecimal initialBalance) {

	public static AccountValues of(Account account) {
		return new AccountValues(account.getName(), account.getType(), account.getCurrency(),
				account.getOpeningDate(), account.getInitialBalance());
	}
}
