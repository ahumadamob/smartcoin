package com.smartcoin.account.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RN-33: qué se edita y cuándo. */
class AccountEditabilityTest {

	static final LocalDate OPENING = LocalDate.of(2026, 8, 1);
	static final AccountValues CURRENT = new AccountValues("Banco", AccountType.BANK, Currency.ARS, OPENING,
			new BigDecimal("1000.00"));

	static final AccountUsage REFERENCED = new AccountUsage(true, false, null);
	static final AccountUsage WITH_CLOSINGS = new AccountUsage(true, true, null);
	static final AccountUsage WITH_ACTIVITY = new AccountUsage(true, false, LocalDate.of(2026, 9, 10));

	private static AccountValues with(String name, AccountType type, Currency currency, LocalDate date, String balance) {
		return new AccountValues(name, type, currency, date, new BigDecimal(balance));
	}

	private static void assertNotEditable(AccountUsage usage, AccountValues requested, String reasonPart) {
		assertThatThrownBy(() -> AccountEditability.of(usage).verifyChange(CURRENT, requested))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_EDITABLE);
					assertThat(e.getMessage()).contains(reasonPart);
				});
	}

	@Test
	void unusedAccountEditsEverything() {
		AccountEditability editability = AccountEditability.of(AccountUsage.unused());

		assertThat(editability.currency()).isEqualTo(new FieldEditability(true, null));
		assertThat(editability.initialBalance().editable()).isTrue();
		assertThat(editability.openingDate().editable()).isTrue();
		assertThatCode(() -> editability.verifyChange(CURRENT,
				with("Otro", AccountType.CASH, Currency.USD, LocalDate.of(2026, 9, 1), "-5.00")))
				.doesNotThrowAnyException();
	}

	@Test
	void nameAndTypeAreAlwaysEditable() {
		AccountValues renamed = with("Nuevo nombre", AccountType.DIGITAL_WALLET, Currency.ARS, OPENING, "1000.00");

		assertThatCode(() -> AccountEditability.of(WITH_CLOSINGS).verifyChange(CURRENT, renamed))
				.doesNotThrowAnyException();
	}

	@Test
	void referencedAccountCannotChangeCurrencyButKeepsTheRest() {
		AccountEditability editability = AccountEditability.of(REFERENCED);

		assertThat(editability.currency().editable()).isFalse();
		assertThat(editability.currency().reason()).isNotBlank();
		assertThat(editability.initialBalance().editable()).isTrue();
		assertThat(editability.openingDate().editable()).isTrue();
		assertNotEditable(REFERENCED, with("Banco", AccountType.BANK, Currency.USD, OPENING, "1000.00"), "moneda");
	}

	@Test
	void accountWithClosingsCannotChangeInitialBalanceNorOpeningDate() {
		AccountEditability editability = AccountEditability.of(WITH_CLOSINGS);

		assertThat(editability.initialBalance().editable()).isFalse();
		assertThat(editability.initialBalance().reason()).contains("cierres");
		assertThat(editability.openingDate().editable()).isFalse();
		assertNotEditable(WITH_CLOSINGS, with("Banco", AccountType.BANK, Currency.ARS, OPENING, "1000.01"),
				"saldo inicial");
		assertNotEditable(WITH_CLOSINGS,
				with("Banco", AccountType.BANK, Currency.ARS, OPENING.plusDays(1), "1000.00"), "fecha de apertura");
	}

	@Test
	void accountWithClosingsAlsoCannotChangeCurrency() {
		assertNotEditable(WITH_CLOSINGS, with("Banco", AccountType.BANK, Currency.USD, OPENING, "1000.00"), "moneda");
	}

	@Test
	void sendingTheSameValuesIsNotAChange() {
		AccountValues sameWithOtherScale = with("Banco", AccountType.BANK, Currency.ARS, OPENING, "1000.0");

		assertThatCode(() -> AccountEditability.of(WITH_CLOSINGS).verifyChange(CURRENT, sameWithOtherScale))
				.doesNotThrowAnyException();
	}

	@Test
	void accountWithoutClosingsCanChangeBalanceEvenIfReferenced() {
		assertThatCode(() -> AccountEditability.of(REFERENCED).verifyChange(CURRENT,
				with("Banco", AccountType.BANK, Currency.ARS, OPENING, "-250.50"))).doesNotThrowAnyException();
	}

	@Test
	void openingDateCanMoveUpToTheFirstMovementOrTransferInclusive() {
		AccountEditability editability = AccountEditability.of(WITH_ACTIVITY);

		assertThatCode(() -> editability.verifyChange(CURRENT,
				with("Banco", AccountType.BANK, Currency.ARS, LocalDate.of(2026, 9, 10), "1000.00")))
				.doesNotThrowAnyException();
		assertThatCode(() -> editability.verifyChange(CURRENT,
				with("Banco", AccountType.BANK, Currency.ARS, LocalDate.of(2026, 8, 15), "1000.00")))
				.doesNotThrowAnyException();
	}

	@Test
	void openingDateCannotEndUpAfterTheFirstMovementOrTransfer() {
		assertNotEditable(WITH_ACTIVITY,
				with("Banco", AccountType.BANK, Currency.ARS, LocalDate.of(2026, 9, 11), "1000.00"), "10/09/2026");
	}

	@Test
	void openingDateCanMoveEarlierWhenThereIsActivity() {
		AccountValues earlier = new AccountValues("Banco", AccountType.BANK, Currency.ARS, LocalDate.of(2026, 7, 1),
				new BigDecimal("1000.00"));

		assertThatCode(() -> AccountEditability.of(WITH_ACTIVITY).verifyChange(CURRENT, earlier))
				.doesNotThrowAnyException();
	}
}
