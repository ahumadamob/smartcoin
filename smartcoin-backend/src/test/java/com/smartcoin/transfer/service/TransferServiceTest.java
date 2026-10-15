package com.smartcoin.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.transfer.domain.Transfer;
import com.smartcoin.transfer.repository.TransferRepository;
import com.smartcoin.transfer.service.TransferService.NewTransfer;
import com.smartcoin.transfer.service.TransferService.TransferRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-27, RN-36, RN-01 y D-24: sin base de datos, con repositorios simulados y el reloj en el 15 de octubre de 2026.
 * El usuario tiene dos cuentas en pesos (banco abierto el 1/8, efectivo abierta el 20/9) y una en dólares.
 */
class TransferServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final long OTHER_USERS_ACCOUNT = 99;

	TransferRepository transfers = mock(TransferRepository.class);
	AccountRepository accounts = mock(AccountRepository.class);
	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	TransferService service;

	Account bank = account(1, "Banco", Currency.ARS, "2026-08-01");
	Account cash = account(2, "Efectivo", Currency.ARS, "2026-09-20");
	Account dollars = account(3, "Caja en dólares", Currency.USD, "2026-08-01");

	@BeforeEach
	void setUp() {
		service = new TransferService(transfers, accounts, periods,
				Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), ZONE));
		when(accounts.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(bank));
		when(accounts.findByIdAndUserId(2L, USER_ID)).thenReturn(Optional.of(cash));
		when(accounts.findByIdAndUserId(3L, USER_ID)).thenReturn(Optional.of(dollars));
		for (YearMonth month : List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10))) {
			period(month, PeriodStatus.OPEN);
		}
		when(transfers.save(any(Transfer.class))).thenAnswer(call -> {
			Transfer t = call.getArgument(0);
			ReflectionTestUtils.setField(t, "id", 31L);
			return t;
		});
	}

	void period(YearMonth month, PeriodStatus status) {
		BudgetPeriod p = BudgetPeriod.open(USER_ID, month);
		p.setStatus(status);
		when(periods.findByUserIdAndPeriodMonth(USER_ID, month)).thenReturn(Optional.of(p));
	}

	static Account account(long id, String name, Currency currency, String openingDate) {
		Account a = new Account();
		ReflectionTestUtils.setField(a, "id", id);
		a.setName(name);
		a.setCurrency(currency);
		a.setOpeningDate(LocalDate.parse(openingDate));
		return a;
	}

	static NewTransfer transfer(long source, long target, String date, String sourceAmount, String targetAmount,
			String note) {
		return new NewTransfer(source, target, LocalDate.parse(date), new BigDecimal(sourceAmount),
				new BigDecimal(targetAmount), note);
	}

	void assertRejected(NewTransfer values, ErrorCode code, String field) {
		assertThatThrownBy(() -> service.create(USER_ID, values)).isInstanceOfSatisfying(BusinessException.class,
				e -> {
					assertThat(e.code()).isEqualTo(code);
					assertThat(e.field()).isEqualTo(field);
				});
		verify(transfers, never()).save(any());
	}

	@Test
	void createsTheTransferWithBothAccountsAndTheUser() {
		TransferRow row = service.create(USER_ID,
				transfer(1, 2, "2026-10-15", "50000.00", "50000.00", "  Extracción  "));

		ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
		verify(transfers).save(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(saved.getValue().getSourceAccount()).isSameAs(bank);
		assertThat(saved.getValue().getTargetAccount()).isSameAs(cash);
		assertThat(saved.getValue().getTransferDate()).isEqualTo(LocalDate.parse("2026-10-15"));
		assertThat(saved.getValue().getSourceAmount()).isEqualByComparingTo("50000.00");
		assertThat(saved.getValue().getTargetAmount()).isEqualByComparingTo("50000.00");
		assertThat(saved.getValue().getNote()).isEqualTo("Extracción");
		assertThat(row.id()).isEqualTo(31L);
		assertThat(row.sourceAccountName()).isEqualTo("Banco");
		assertThat(row.targetCurrency()).isEqualTo(Currency.ARS);
	}

	@Test
	void aBlankNoteIsStoredAsNoNote() {
		service.create(USER_ID, transfer(1, 2, "2026-10-15", "10.00", "10.00", "   "));

		ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
		verify(transfers).save(saved.capture());
		assertThat(saved.getValue().getNote()).isNull();
	}

	@Test
	void theSameAccountAsSourceAndTargetIsAValidationErrorOnTheTarget() {
		assertRejected(transfer(1, 1, "2026-10-15", "10.00", "10.00", null), ErrorCode.VALIDATION_ERROR,
				"targetAccountId");
	}

	@Test
	void aSourceThatDoesNotExistOrBelongsToAnotherUserIsAValidationErrorOnTheSource() {
		when(accounts.findByIdAndUserId(OTHER_USERS_ACCOUNT, USER_ID)).thenReturn(Optional.empty());

		assertRejected(transfer(OTHER_USERS_ACCOUNT, 2, "2026-10-15", "10.00", "10.00", null),
				ErrorCode.VALIDATION_ERROR, "sourceAccountId");
	}

	@Test
	void aTargetThatDoesNotExistOrBelongsToAnotherUserIsAValidationErrorOnTheTarget() {
		when(accounts.findByIdAndUserId(OTHER_USERS_ACCOUNT, USER_ID)).thenReturn(Optional.empty());

		assertRejected(transfer(1, OTHER_USERS_ACCOUNT, "2026-10-15", "10.00", "10.00", null),
				ErrorCode.VALIDATION_ERROR, "targetAccountId");
	}

	@Test
	void everyAccountLookupCarriesTheUserOfTheRequest() {
		service.create(USER_ID, transfer(1, 2, "2026-10-15", "10.00", "10.00", null));

		verify(accounts).findByIdAndUserId(1L, USER_ID);
		verify(accounts).findByIdAndUserId(2L, USER_ID);
		verifyNoMoreInteractions(accounts);
		verify(accounts, never()).findById(anyLong());
		verify(accounts, never()).findByIdAndUserId(anyLong(), org.mockito.ArgumentMatchers.eq(OTHER_USER_ID));
	}

	@Test
	void differentAmountsInTheSameCurrencyAreRejected() {
		assertRejected(transfer(1, 2, "2026-10-15", "50000.00", "49999.99", null),
				ErrorCode.TRANSFER_AMOUNTS_MISMATCH, null);
	}

	@Test
	void equalAmountsWithDifferentScaleAreAccepted() {
		service.create(USER_ID, transfer(1, 2, "2026-10-15", "50000", "50000.00", null));

		verify(transfers).save(any(Transfer.class));
	}

	@Test
	void aTransferBetweenDifferentCurrenciesIsAcceptedWithItsTwoAmounts() {
		TransferRow row = service.create(USER_ID,
				transfer(1, 3, "2026-10-15", "1250000.00", "1000.00", "Compra de dólares"));

		ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
		verify(transfers).save(saved.capture());
		assertThat(saved.getValue().getSourceAmount()).isEqualByComparingTo("1250000.00");
		assertThat(saved.getValue().getTargetAmount()).isEqualByComparingTo("1000.00");
		assertThat(row.sourceCurrency()).isEqualTo(Currency.ARS);
		assertThat(row.targetCurrency()).isEqualTo(Currency.USD);
		assertThat(row.exchangeRate()).isEqualByComparingTo("1250.00");
	}

	@Test
	void aSaleOfDollarsHasTheRateOfArsOverUsdWhateverTheSource() {
		TransferRow row = service.create(USER_ID, transfer(3, 1, "2026-10-15", "500.00", "610000.00", null));

		assertThat(row.exchangeRate()).isEqualByComparingTo("1220.00");
	}

	@Test
	void aTransferInTheSameCurrencyHasNoExchangeRate() {
		TransferRow row = service.create(USER_ID, transfer(1, 2, "2026-10-15", "50000.00", "50000.00", null));

		assertThat(row.exchangeRate()).isNull();
	}

	@Test
	void listCarriesTheRateOnlyInTheOnesBetweenDifferentCurrencies() {
		when(transfers.findByUserIdAndDateBetween(eq(USER_ID), any(), any())).thenReturn(List.of(
				saved(1, 3, "1250000.00", "1000.00"), saved(1, 2, "10.00", "10.00")));

		List<TransferRow> rows = service.list(USER_ID, null, null);

		assertThat(rows.get(0).exchangeRate()).isEqualByComparingTo("1250.00");
		assertThat(rows.get(1).exchangeRate()).isNull();
	}

	private Transfer saved(long source, long target, String sourceAmount, String targetAmount) {
		Map<Long, Account> byId = Map.of(1L, bank, 2L, cash, 3L, dollars);
		Transfer t = new Transfer();
		t.setUserId(USER_ID);
		t.setSourceAccount(byId.get(source));
		t.setTargetAccount(byId.get(target));
		t.setTransferDate(LocalDate.parse("2026-10-15"));
		t.setSourceAmount(new BigDecimal(sourceAmount));
		t.setTargetAmount(new BigDecimal(targetAmount));
		return t;
	}

	@Test
	void aFutureDateIsOutOfRange() {
		assertRejected(transfer(1, 2, "2026-10-16", "10.00", "10.00", null), ErrorCode.DATE_OUT_OF_RANGE, null);
	}

	@Test
	void aDateBeforeTheOpeningOfTheTargetIsOutOfRangeEvenIfTheSourceIsOpen() {
		assertRejected(transfer(1, 2, "2026-09-19", "10.00", "10.00", null), ErrorCode.DATE_OUT_OF_RANGE, null);
	}

	@Test
	void aDateBeforeTheOpeningOfTheSourceIsOutOfRange() {
		assertRejected(transfer(2, 1, "2026-09-19", "10.00", "10.00", null), ErrorCode.DATE_OUT_OF_RANGE, null);
	}

	@Test
	void theOpeningDayOfBothAccountsIsAccepted() {
		service.create(USER_ID, transfer(1, 2, "2026-09-20", "10.00", "10.00", null));

		verify(transfers).save(any(Transfer.class));
	}

	@Test
	void aDateInAClosedMonthIsPeriodClosed() {
		period(YearMonth.of(2026, 9), PeriodStatus.CLOSED);

		assertRejected(transfer(1, 2, "2026-09-25", "10.00", "10.00", null), ErrorCode.PERIOD_CLOSED, null);
	}

	@Test
	void aMonthWithoutPeriodIsOutOfRange() {
		Account early = account(4, "Vieja", Currency.ARS, "2026-01-01");
		when(accounts.findByIdAndUserId(4L, USER_ID)).thenReturn(Optional.of(early));
		when(periods.findByUserIdAndPeriodMonth(USER_ID, YearMonth.of(2026, 7))).thenReturn(Optional.empty());
		Account early2 = account(5, "Vieja 2", Currency.ARS, "2026-01-01");
		when(accounts.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(early2));

		assertRejected(transfer(4, 5, "2026-07-15", "10.00", "10.00", null), ErrorCode.DATE_OUT_OF_RANGE, null);
	}

	@Test
	void whenSeveralConditionsFailTheFirstInTheDocumentedOrderIsReported() {
		period(YearMonth.of(2026, 9), PeriodStatus.CLOSED);
		// Misma cuenta y montos distintos y fecha futura: gana la misma cuenta.
		assertRejected(transfer(1, 1, "2026-10-16", "10.00", "20.00", null), ErrorCode.VALIDATION_ERROR,
				"targetAccountId");
		// Montos distintos y fecha futura: gana el 409 de los montos.
		assertRejected(transfer(1, 2, "2026-10-16", "10.00", "20.00", null),
				ErrorCode.TRANSFER_AMOUNTS_MISMATCH, null);
		// Fecha futura y mes cerrado a la vez no se puede dar; fecha anterior a la apertura gana a mes cerrado.
		assertRejected(transfer(1, 2, "2026-09-10", "10.00", "10.00", null), ErrorCode.DATE_OUT_OF_RANGE, null);
		// Cuenta inexistente y montos distintos: gana la cuenta.
		when(accounts.findByIdAndUserId(OTHER_USERS_ACCOUNT, USER_ID)).thenReturn(Optional.empty());
		assertRejected(transfer(1, OTHER_USERS_ACCOUNT, "2026-10-15", "10.00", "20.00", null),
				ErrorCode.VALIDATION_ERROR, "targetAccountId");
	}

	@Test
	void aTransferNeverTouchesEntriesOrPeriods() {
		service.create(USER_ID, transfer(1, 2, "2026-10-15", "10.00", "10.00", null));

		// Solo lee el estado del mes de la fecha; no guarda ni modifica períodos.
		verify(periods).findByUserIdAndPeriodMonth(USER_ID, YearMonth.of(2026, 10));
		verifyNoMoreInteractions(periods);
	}

	@Test
	void listDefaultsToTheCurrentMonthUpToToday() {
		service.list(USER_ID, null, null);

		verify(transfers).findByUserIdAndDateBetween(USER_ID, LocalDate.parse("2026-10-01"),
				LocalDate.parse("2026-10-15"));
	}

	@Test
	void listUsesTheGivenRangeAndCompletesTheMissingEnd() {
		service.list(USER_ID, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
		service.list(USER_ID, LocalDate.parse("2026-08-10"), null);
		service.list(USER_ID, null, LocalDate.parse("2026-09-30"));

		// Sin from, el primer día del mes de to: la primera y la tercera llamada piden el mismo rango.
		verify(transfers, org.mockito.Mockito.times(2)).findByUserIdAndDateBetween(USER_ID,
				LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
		verify(transfers).findByUserIdAndDateBetween(USER_ID, LocalDate.parse("2026-08-10"),
				LocalDate.parse("2026-10-15"));
	}

	@Test
	void anInvertedRangeIsAValidationErrorOnFromAndReadsNothing() {
		assertThatThrownBy(() -> service.list(USER_ID, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-01")))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("from");
				});
		verifyNoInteractions(transfers);
	}
}
