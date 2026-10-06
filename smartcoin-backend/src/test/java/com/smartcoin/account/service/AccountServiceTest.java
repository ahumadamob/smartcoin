package com.smartcoin.account.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.domain.AccountTotal;
import com.smartcoin.account.domain.AccountType;
import com.smartcoin.account.domain.AccountValues;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.AccountClosingRepository;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.transfer.repository.TransferRepository;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** HU-07, RN-33 y RN-01: sin base de datos, con repositorios simulados y un reloj fijo. */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final long ACCOUNT_ID = 42;
	static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
	static final LocalDate OPENING = LocalDate.of(2026, 8, 1);

	@Mock
	AccountRepository accounts;
	@Mock
	BudgetItemRepository items;
	@Mock
	BudgetEntryRepository entries;
	@Mock
	MovementRepository movements;
	@Mock
	TransferRepository transfers;
	@Mock
	AccountClosingRepository closings;
	@Mock
	UserRepository users;

	AccountService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(Instant.parse("2026-10-06T15:00:00Z"), ZoneId.of("America/Argentina/Mendoza"));
		service = new AccountService(accounts, items, entries, movements, transfers, closings, users, clock);
	}

	private void userWithStart(YearMonth start) {
		User user = new User();
		user.setStartPeriod(start);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
	}

	private static AccountValues values(String name, Currency currency, LocalDate openingDate, String balance) {
		return new AccountValues(name, AccountType.BANK, currency, openingDate, new BigDecimal(balance));
	}

	private Account existing() {
		Account account = newAccount();
		when(accounts.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
		return account;
	}

	private static Account newAccount() {
		Account account = new Account();
		ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
		account.setUserId(USER_ID);
		account.setName("Banco");
		account.setType(AccountType.BANK);
		account.setCurrency(Currency.ARS);
		account.setOpeningDate(OPENING);
		account.setInitialBalance(new BigDecimal("1000.00"));
		return account;
	}

	/** Los cinco tipos de referencia que bloquean la moneda y la eliminación (RN-33). */
	enum Reference {
		BUDGET_ITEM, BUDGET_ENTRY, MOVEMENT, TRANSFER, CLOSING
	}

	private void referencedBy(Reference reference) {
		switch (reference) {
			case BUDGET_ITEM -> when(items.existsByUserIdAndAccountId(USER_ID, ACCOUNT_ID)).thenReturn(true);
			case BUDGET_ENTRY -> when(entries.existsByUserIdAndAccountId(USER_ID, ACCOUNT_ID)).thenReturn(true);
			case MOVEMENT -> when(movements.findFirstMovementDate(USER_ID, ACCOUNT_ID)).thenReturn(TODAY);
			case TRANSFER -> when(transfers.findFirstTransferDate(USER_ID, ACCOUNT_ID)).thenReturn(TODAY);
			case CLOSING -> when(closings.existsByUserIdAndAccountId(USER_ID, ACCOUNT_ID)).thenReturn(true);
		}
	}

	private void saveReturnsTheArgument() {
		when(accounts.saveAndFlush(any(Account.class))).thenAnswer(i -> i.getArgument(0));
	}

	private static void assertCode(Throwable thrown, ErrorCode code) {
		assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));
	}

	// --- alta ---

	@Test
	void createSavesTheAccountForTheCurrentUserWithTrimmedNameAndTwoDecimals() {
		userWithStart(YearMonth.of(2026, 8));
		saveReturnsTheArgument();

		AccountService.AccountView created = service.create(USER_ID,
				values("  Caja de ahorro ", Currency.USD, OPENING, "-250.5"));

		Account account = created.account();
		assertThat(account.getUserId()).isEqualTo(USER_ID);
		assertThat(account.getName()).isEqualTo("Caja de ahorro");
		assertThat(account.getCurrency()).isEqualTo(Currency.USD);
		assertThat(account.getInitialBalance()).isEqualByComparingTo("-250.50");
		assertThat(account.getInitialBalance().scale()).isEqualTo(2);
		assertThat(created.editability().currency().editable()).isTrue();
		verify(accounts).existsByUserIdAndName(USER_ID, "Caja de ahorro");
	}

	@Test
	void createAcceptsTheFirstDayOfTheStartPeriodAndToday() {
		userWithStart(YearMonth.of(2026, 8));
		saveReturnsTheArgument();

		service.create(USER_ID, values("A", Currency.ARS, LocalDate.of(2026, 8, 1), "0"));
		service.create(USER_ID, values("B", Currency.ARS, TODAY, "0"));

		verify(accounts, times(2)).saveAndFlush(any(Account.class));
	}

	@Test
	void createRejectsTheDayBeforeTheStartPeriodAndTomorrow() {
		userWithStart(YearMonth.of(2026, 8));

		assertThatThrownBy(() -> service.create(USER_ID, values("A", Currency.ARS, LocalDate.of(2026, 7, 31), "0")))
				.satisfies(e -> assertCode(e, ErrorCode.VALIDATION_ERROR));
		assertThatThrownBy(() -> service.create(USER_ID, values("A", Currency.ARS, TODAY.plusDays(1), "0")))
				.satisfies(e -> assertCode(e, ErrorCode.VALIDATION_ERROR));

		verify(accounts, never()).saveAndFlush(any());
	}

	@Test
	void createRejectsARepeatedNameForTheSameUser() {
		userWithStart(YearMonth.of(2026, 8));
		when(accounts.existsByUserIdAndName(USER_ID, "banco")).thenReturn(true);

		assertThatThrownBy(() -> service.create(USER_ID, values("banco", Currency.ARS, OPENING, "0")))
				.satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_NAME_TAKEN));
		verify(accounts, never()).saveAndFlush(any());
	}

	@Test
	void theNameIsCheckedOnlyAmongTheCurrentUsersAccounts() {
		userWithStart(YearMonth.of(2026, 8));
		saveReturnsTheArgument();

		// Dos usuarios pueden tener "Banco": la verificación es siempre por el userId del usuario actual.
		service.create(USER_ID, values("Banco", Currency.ARS, OPENING, "0"));

		verify(accounts).existsByUserIdAndName(USER_ID, "Banco");
		verify(accounts, never()).existsByUserIdAndName(eq(OTHER_USER_ID), any());
	}

	@Test
	void createTurnsAUniqueIndexRaceIntoNameTaken() {
		userWithStart(YearMonth.of(2026, 8));
		when(accounts.saveAndFlush(any(Account.class))).thenThrow(new DataIntegrityViolationException("uk_account_user_name"));

		assertThatThrownBy(() -> service.create(USER_ID, values("Banco", Currency.ARS, OPENING, "0")))
				.satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_NAME_TAKEN));
	}

	// --- edición ---

	@Test
	void updateChangesNameAndTypeEvenWhenTheAccountIsFullyUsed() {
		Account account = existing();
		when(closings.existsByUserIdAndAccountId(USER_ID, ACCOUNT_ID)).thenReturn(true);
		saveReturnsTheArgument();

		AccountService.AccountView updated = service.update(USER_ID, ACCOUNT_ID,
				new AccountValues("Banco Nación", AccountType.DIGITAL_WALLET, Currency.ARS, OPENING,
						new BigDecimal("1000.00")));

		assertThat(account.getName()).isEqualTo("Banco Nación");
		assertThat(account.getType()).isEqualTo(AccountType.DIGITAL_WALLET);
		assertThat(updated.editability().initialBalance().editable()).isFalse();
	}

	@Test
	void updateRenamingToAnotherCaseOfItsOwnNameIsAllowed() {
		Account account = existing();
		saveReturnsTheArgument();
		// Con la colación de la base, "BANCO" coincide con "Banco", pero es la misma cuenta: se excluye por id.
		when(accounts.existsByUserIdAndNameAndIdNot(USER_ID, "BANCO", ACCOUNT_ID)).thenReturn(false);

		service.update(USER_ID, ACCOUNT_ID, values("BANCO", Currency.ARS, OPENING, "1000"));

		assertThat(account.getName()).isEqualTo("BANCO");
	}

	@Test
	void updateRejectsRenamingToAnotherAccountsName() {
		Account account = existing();
		when(accounts.existsByUserIdAndNameAndIdNot(USER_ID, "Efectivo", ACCOUNT_ID)).thenReturn(true);

		assertThatThrownBy(() -> service.update(USER_ID, ACCOUNT_ID, values("Efectivo", Currency.ARS, OPENING, "1000")))
				.satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_NAME_TAKEN));
		assertThat(account.getName()).isEqualTo("Banco");
		verify(accounts, never()).saveAndFlush(any());
	}

	@Test
	void updateCanChangeCurrencyBalanceAndDateOfAnUnusedAccount() {
		Account account = existing();
		userWithStart(YearMonth.of(2026, 8));
		saveReturnsTheArgument();

		service.update(USER_ID, ACCOUNT_ID, values("Banco", Currency.USD, LocalDate.of(2026, 9, 1), "-10"));

		assertThat(account.getCurrency()).isEqualTo(Currency.USD);
		assertThat(account.getOpeningDate()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(account.getInitialBalance()).isEqualByComparingTo("-10.00");
	}

	@ParameterizedTest
	@EnumSource(Reference.class)
	void updateRejectsChangingCurrencyWhenTheAccountIsReferenced(Reference reference) {
		existing();
		referencedBy(reference);

		assertThatThrownBy(() -> service.update(USER_ID, ACCOUNT_ID, values("Banco", Currency.USD, OPENING, "1000")))
				.satisfies(e -> assertCode(e, ErrorCode.FIELD_NOT_EDITABLE));
		verify(accounts, never()).saveAndFlush(any());
	}

	@Test
	void updateRejectsChangingBalanceOrDateWhenTheAccountHasClosings() {
		existing();
		userWithStart(YearMonth.of(2026, 8));
		when(closings.existsByUserIdAndAccountId(USER_ID, ACCOUNT_ID)).thenReturn(true);

		assertThatThrownBy(() -> service.update(USER_ID, ACCOUNT_ID, values("Banco", Currency.ARS, OPENING, "1000.01")))
				.satisfies(e -> assertCode(e, ErrorCode.FIELD_NOT_EDITABLE));
		assertThatThrownBy(() -> service.update(USER_ID, ACCOUNT_ID,
				values("Banco", Currency.ARS, LocalDate.of(2026, 9, 1), "1000")))
				.satisfies(e -> assertCode(e, ErrorCode.FIELD_NOT_EDITABLE));
	}

	@Test
	void updateAcceptsTheSameValuesOnAnAccountWithClosings() {
		existing();
		when(closings.existsByUserIdAndAccountId(USER_ID, ACCOUNT_ID)).thenReturn(true);
		saveReturnsTheArgument();

		service.update(USER_ID, ACCOUNT_ID, values("Banco", Currency.ARS, OPENING, "1000"));

		verify(accounts).saveAndFlush(any(Account.class));
	}

	@Test
	void updateRejectsAnOpeningDateAfterTheFirstMovementOrTransfer() {
		existing();
		userWithStart(YearMonth.of(2026, 8));
		when(movements.findFirstMovementDate(USER_ID, ACCOUNT_ID)).thenReturn(LocalDate.of(2026, 9, 20));
		when(transfers.findFirstTransferDate(USER_ID, ACCOUNT_ID)).thenReturn(LocalDate.of(2026, 9, 10));
		saveReturnsTheArgument();

		assertThatThrownBy(() -> service.update(USER_ID, ACCOUNT_ID,
				values("Banco", Currency.ARS, LocalDate.of(2026, 9, 11), "1000")))
				.satisfies(e -> assertCode(e, ErrorCode.FIELD_NOT_EDITABLE));
		// El tope es la fecha más temprana de las dos y es inclusivo.
		service.update(USER_ID, ACCOUNT_ID, values("Banco", Currency.ARS, LocalDate.of(2026, 9, 10), "1000"));
		verify(accounts).saveAndFlush(any(Account.class));
	}

	@Test
	void updateChecksTheOpeningDateRangeBeforeEditability() {
		existing();
		userWithStart(YearMonth.of(2026, 8));

		assertThatThrownBy(() -> service.update(USER_ID, ACCOUNT_ID,
				values("Banco", Currency.ARS, TODAY.plusDays(1), "1000")))
				.satisfies(e -> assertCode(e, ErrorCode.VALIDATION_ERROR));
		assertThatThrownBy(() -> service.update(USER_ID, ACCOUNT_ID,
				values("Banco", Currency.ARS, LocalDate.of(2026, 7, 31), "1000")))
				.satisfies(e -> assertCode(e, ErrorCode.VALIDATION_ERROR));
	}

	// --- eliminación ---

	@Test
	void deleteRemovesAnUnreferencedAccount() {
		Account account = existing();

		service.delete(USER_ID, ACCOUNT_ID);

		verify(accounts).delete(account);
	}

	@ParameterizedTest
	@EnumSource(Reference.class)
	void deleteRejectsAnAccountReferencedByAnyKindOfRecord(Reference reference) {
		Account account = existing();
		referencedBy(reference);

		assertThatThrownBy(() -> service.delete(USER_ID, ACCOUNT_ID))
				.satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_IN_USE));
		verify(accounts, never()).delete(account);
	}

	@Test
	void deleteTurnsAForeignKeyViolationIntoAccountInUse() {
		Account account = existing();
		doThrow(new DataIntegrityViolationException("fk")).when(accounts).flush();

		assertThatThrownBy(() -> service.delete(USER_ID, ACCOUNT_ID))
				.satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_IN_USE));
		verify(accounts).delete(account);
	}

	// --- aislamiento entre usuarios (HU-06, RN-01) ---

	@Test
	void anotherUsersAccountIsNotFoundWhenReadingUpdatingOrDeleting() {
		// El repositorio no devuelve nada para este userId: la cuenta existe, pero es de otro usuario.
		when(accounts.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(USER_ID, ACCOUNT_ID)).satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> service.update(USER_ID, ACCOUNT_ID, values("X", Currency.ARS, OPENING, "0")))
				.satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> service.delete(USER_ID, ACCOUNT_ID)).satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));

		verify(accounts, never()).saveAndFlush(any());
		verify(accounts, never()).delete(any());
		verify(accounts, never()).findById(anyLong());
	}

	@Test
	void listQueriesOnlyWithTheCurrentUsersId() {
		Account account = newAccount();
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID)).thenReturn(List.of(account));

		AccountService.AccountList list = service.list(USER_ID);

		assertThat(list.accounts()).hasSize(1);
		verify(accounts).findByUserIdOrderByCurrencyAscNameAsc(USER_ID);
		verify(closings).existsByUserIdAndAccountId(USER_ID, ACCOUNT_ID);
		verify(movements).findFirstMovementDate(USER_ID, ACCOUNT_ID);
		verify(transfers).findFirstTransferDate(USER_ID, ACCOUNT_ID);
		verify(movements).sumByAccountUpTo(USER_ID, TODAY, EntryKind.INCOME);
		verify(movements).sumByAccountUpTo(USER_ID, TODAY, EntryKind.EXPENSE);
		verify(transfers).sumIncomingByAccountUpTo(USER_ID, TODAY);
		verify(transfers).sumOutgoingByAccountUpTo(USER_ID, TODAY);
		verifyNoMoreInteractions(accounts, movements, transfers);
	}

	// --- saldo actual y subtotales por moneda (HU-08, RN-35, RN-04) ---

	private static Account account(long id, Currency currency, String initialBalance) {
		Account account = newAccount();
		ReflectionTestUtils.setField(account, "id", id);
		account.setName("Cuenta " + id);
		account.setCurrency(currency);
		account.setInitialBalance(new BigDecimal(initialBalance));
		return account;
	}

	private static AccountTotal total(long accountId, String amount) {
		return new AccountTotal(accountId, new BigDecimal(amount));
	}

	@Test
	void anAccountWithoutActivityShowsItsInitialBalance() {
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID))
				.thenReturn(List.of(account(1, Currency.ARS, "100000.00")));

		AccountService.AccountList list = service.list(USER_ID);

		assertThat(list.accounts().get(0).currentBalance()).isEqualTo(new BigDecimal("100000.00"));
		assertThat(list.subtotals()).containsExactly(
				new AccountService.CurrencySubtotal(Currency.ARS, new BigDecimal("100000.00")));
	}

	@Test
	void theBalanceCombinesMovementsAndTransfersOfTheAccount() {
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID))
				.thenReturn(List.of(account(1, Currency.ARS, "100000.00"), account(2, Currency.ARS, "0.00")));
		when(movements.sumByAccountUpTo(USER_ID, TODAY, EntryKind.INCOME)).thenReturn(List.of(total(1, "50000.00")));
		when(movements.sumByAccountUpTo(USER_ID, TODAY, EntryKind.EXPENSE)).thenReturn(List.of(total(1, "20000.00")));
		when(transfers.sumOutgoingByAccountUpTo(USER_ID, TODAY)).thenReturn(List.of(total(1, "30000.00")));
		when(transfers.sumIncomingByAccountUpTo(USER_ID, TODAY)).thenReturn(List.of(total(2, "30000.00")));

		AccountService.AccountList list = service.list(USER_ID);

		assertThat(list.accounts().get(0).currentBalance()).isEqualTo(new BigDecimal("100000.00"));
		assertThat(list.accounts().get(1).currentBalance()).isEqualTo(new BigDecimal("30000.00"));
		assertThat(list.subtotals()).containsExactly(
				new AccountService.CurrencySubtotal(Currency.ARS, new BigDecimal("130000.00")));
	}

	@Test
	void anAdvanceSalaryCountsFromTheDateOfThePaymentEvenIfItsEntryIsOfNextMonth() {
		// El repositorio suma por la fecha del movimiento (<= hoy), sin mirar el período de la partida: el sueldo
		// de noviembre cobrado el 02/10 ya figura en lo que devuelve para hoy (06/10).
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID))
				.thenReturn(List.of(account(1, Currency.ARS, "10000.00")));
		when(movements.sumByAccountUpTo(USER_ID, TODAY, EntryKind.INCOME)).thenReturn(List.of(total(1, "800000.00")));

		assertThat(service.list(USER_ID).accounts().get(0).currentBalance()).isEqualTo(new BigDecimal("810000.00"));
	}

	@Test
	void theBalanceIsAskedForTodayFromTheClockNotForALaterDate() {
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID)).thenReturn(List.of());

		service.list(USER_ID);

		// Con el reloj en 06/10/2026 en Mendoza: lo que tenga fecha posterior queda fuera de la consulta.
		verify(movements, times(2)).sumByAccountUpTo(eq(USER_ID), eq(LocalDate.of(2026, 10, 6)), any());
		verify(transfers).sumIncomingByAccountUpTo(USER_ID, LocalDate.of(2026, 10, 6));
		verify(transfers).sumOutgoingByAccountUpTo(USER_ID, LocalDate.of(2026, 10, 6));
	}

	@Test
	void subtotalsAreSeparatedByCurrencyAndNeverMixed() {
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID)).thenReturn(List.of(
				account(1, Currency.ARS, "100000.00"), account(2, Currency.ARS, "-2500.50"),
				account(3, Currency.USD, "250.00")));
		when(transfers.sumIncomingByAccountUpTo(USER_ID, TODAY)).thenReturn(List.of(total(3, "1000.00")));

		AccountService.AccountList list = service.list(USER_ID);

		assertThat(list.subtotals()).containsExactly(
				new AccountService.CurrencySubtotal(Currency.ARS, new BigDecimal("97499.50")),
				new AccountService.CurrencySubtotal(Currency.USD, new BigDecimal("1250.00")));
	}

	@Test
	void onlyCurrenciesWithAccountsHaveASubtotal() {
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID))
				.thenReturn(List.of(account(3, Currency.USD, "250.00")));

		assertThat(service.list(USER_ID).subtotals()).extracting(AccountService.CurrencySubtotal::currency)
				.containsExactly(Currency.USD);
	}

	@Test
	void sumsOfOtherUsersAreNeverRequestedNorAdded() {
		// Todas las consultas de suma reciben el userId del usuario actual, y el repositorio filtra por él: lo de
		// otro usuario no llega. Un total que apunte a una cuenta ajena no suma en ninguna cuenta de la lista.
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID))
				.thenReturn(List.of(account(1, Currency.ARS, "1000.00")));
		when(movements.sumByAccountUpTo(USER_ID, TODAY, EntryKind.INCOME)).thenReturn(List.of(total(999, "5000.00")));

		AccountService.AccountList list = service.list(USER_ID);

		assertThat(list.accounts().get(0).currentBalance()).isEqualTo(new BigDecimal("1000.00"));
		assertThat(list.subtotals().get(0).balance()).isEqualTo(new BigDecimal("1000.00"));
		verify(movements, never()).sumByAccountUpTo(eq(OTHER_USER_ID), any(), any());
		verify(transfers, never()).sumIncomingByAccountUpTo(eq(OTHER_USER_ID), any());
		verify(transfers, never()).sumOutgoingByAccountUpTo(eq(OTHER_USER_ID), any());
	}

	@Test
	void getAlsoReturnsTheCurrentBalance() {
		existing();
		when(movements.sumByAccountUpTo(USER_ID, TODAY, EntryKind.INCOME)).thenReturn(List.of());
		when(movements.sumByAccountUpTo(USER_ID, TODAY, EntryKind.EXPENSE)).thenReturn(List.of(total(ACCOUNT_ID, "200.50")));

		assertThat(service.get(USER_ID, ACCOUNT_ID).currentBalance()).isEqualTo(new BigDecimal("799.50"));
	}
}
