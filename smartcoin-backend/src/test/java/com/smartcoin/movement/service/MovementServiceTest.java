package com.smartcoin.movement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.domain.Movement;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.movement.service.MovementService.MovementRow;
import com.smartcoin.movement.service.MovementService.NewMovement;
import com.smartcoin.movement.service.MovementService.Registered;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-19, RN-21, RN-23, RN-01 y D-24: sin base de datos, con repositorios simulados y el reloj en el 20 de noviembre de
 * 2026. El usuario tiene dos cuentas en pesos y una en dólares, y la partida Expensas de noviembre (120.000,00). Un
 * repositorio de movimientos en memoria suma de verdad lo que se registra, para que los valores derivados salgan de
 * los movimientos y no de lo que el test les dice.
 */
class MovementServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final long ENTRY_ID = 900;
	static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	AccountRepository accounts = mock(AccountRepository.class);
	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	List<Movement> stored = new ArrayList<>();
	MovementService service;

	Account pesos = account(42, "Banco", Currency.ARS, "2026-08-01");
	Account otherPesos = account(44, "Billetera", Currency.ARS, "2026-08-01");
	Account dollars = account(43, "Caja en dólares", Currency.USD, "2026-08-01");
	BudgetPeriod november;
	BudgetEntry expensas;

	@BeforeEach
	void setUp() {
		service = serviceAt("2026-11-20T15:00:00Z", 10);
		november = period(NOVEMBER, 100, PeriodStatus.OPEN);
		expensas = entry(november, EntryKind.EXPENSE, pesos, "120000.00");
		when(accounts.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(pesos));
		when(accounts.findByIdAndUserId(44L, USER_ID)).thenReturn(Optional.of(otherPesos));
		when(accounts.findByIdAndUserId(43L, USER_ID)).thenReturn(Optional.of(dollars));
		when(movements.save(any(Movement.class))).thenAnswer(call -> {
			Movement m = call.getArgument(0);
			ReflectionTestUtils.setField(m, "id", 5000L + stored.size());
			stored.add(m);
			return m;
		});
		when(movements.sumByEntry(anyLong(), anyLong())).thenAnswer(call -> stored.stream()
				.filter(m -> m.getEntry().getId().equals(call.getArgument(1)))
				.map(Movement::getAmount).reduce(BigDecimal::add).orElse(null));
	}

	MovementService serviceAt(String instant, int earlyDays) {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, earlyDays),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		return new MovementService(entries, movements, accounts, periods, properties,
				Clock.fixed(Instant.parse(instant), ZONE));
	}

	BudgetPeriod period(YearMonth month, long id, PeriodStatus status) {
		BudgetPeriod p = BudgetPeriod.open(USER_ID, month);
		ReflectionTestUtils.setField(p, "id", id);
		p.setStatus(status);
		when(periods.findByUserIdAndPeriodMonth(USER_ID, month)).thenReturn(Optional.of(p));
		return p;
	}

	BudgetEntry entry(BudgetPeriod period, EntryKind kind, Account account, String budgeted) {
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", ENTRY_ID);
		e.setUserId(USER_ID);
		e.setPeriod(period);
		e.setOrigin(EntryOrigin.ONE_OFF);
		e.setName("Expensas");
		e.setKind(kind);
		e.setAccount(account);
		e.setDueDate(period.getPeriodMonth().atDay(10));
		e.setBudgetedAmount(new BigDecimal(budgeted));
		e.setManual(false);
		e.setStatus(StoredEntryStatus.PENDING);
		when(entries.findByIdAndUserIdWithDetails(ENTRY_ID, USER_ID)).thenReturn(Optional.of(e));
		return e;
	}

	static Account account(long id, String name, Currency currency, String openingDate) {
		Account a = new Account();
		ReflectionTestUtils.setField(a, "id", id);
		a.setName(name);
		a.setCurrency(currency);
		a.setOpeningDate(LocalDate.parse(openingDate));
		return a;
	}

	static NewMovement movement(String date, String amount, long accountId) {
		return new NewMovement(LocalDate.parse(date), new BigDecimal(amount), accountId, null);
	}

	Registered register(String date, String amount, long accountId) {
		return service.register(USER_ID, ENTRY_ID, movement(date, amount, accountId));
	}

	void assertRejected(NewMovement values, ErrorCode code) {
		assertThatThrownBy(() -> service.register(USER_ID, ENTRY_ID, values))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));
		verify(movements, never()).save(any());
		assertThat(stored).isEmpty();
	}

	@Nested
	class DerivedValues {

		@Test
		void theExampleOfTheStoryTwoPartialPaymentsLeaveItPartialWithNothingPending() {
			// Expensas de 120.000,00 con 70.000,00 el 05/11 y 50.000,00 el 12/11.
			Registered first = register("2026-11-05", "70000.00", 42);

			assertThat(first.entry().amounts().actual()).isEqualByComparingTo("70000.00");
			assertThat(first.entry().amounts().pending()).isEqualByComparingTo("50000.00");
			assertThat(first.entry().amounts().status()).isEqualTo(EntryStatus.PARTIAL);

			Registered second = register("2026-11-12", "50000.00", 42);

			assertThat(second.entry().amounts().actual()).isEqualByComparingTo("120000.00");
			assertThat(second.entry().amounts().pending()).isEqualByComparingTo("0.00");
			assertThat(second.entry().amounts().forecast()).isEqualByComparingTo("120000.00");
			// Sigue Parcial hasta consolidar: registrar no consolida (RN-23).
			assertThat(second.entry().amounts().status()).isEqualTo(EntryStatus.PARTIAL);
			assertThat(second.entry().budgetedAmount()).isEqualByComparingTo("120000.00");
			assertThat(expensas.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
			assertThat(stored).hasSize(2);
		}

		@Test
		void aPaymentAboveTheBudgetLeavesNothingPendingAndTheForecastIsTheReal() {
			expensas.setBudgetedAmount(new BigDecimal("45000.00"));

			Registered result = register("2026-11-10", "50000.00", 42);

			assertThat(result.entry().amounts().actual()).isEqualByComparingTo("50000.00");
			assertThat(result.entry().amounts().pending()).isEqualByComparingTo("0.00");
			assertThat(result.entry().amounts().forecast()).isEqualByComparingTo("50000.00");
			assertThat(result.entry().amounts().status()).isEqualTo(EntryStatus.PARTIAL);
			// El presupuestado no se ajusta: queda en 45.000,00 (RN-23).
			assertThat(result.entry().budgetedAmount()).isEqualByComparingTo("45000.00");
			assertThat(expensas.getBudgetedAmount()).isEqualByComparingTo("45000.00");
		}

		@Test
		void registeringWhenThePendingIsAlreadyZeroIsAllowed() {
			register("2026-11-05", "120000.00", 42);

			Registered extra = register("2026-11-06", "1000.00", 42);

			assertThat(extra.entry().amounts().actual()).isEqualByComparingTo("121000.00");
			assertThat(extra.entry().amounts().pending()).isEqualByComparingTo("0.00");
		}

		@Test
		void registeringChangesNothingStoredInTheEntry() {
			register("2026-11-05", "70000.00", 44);

			assertThat(expensas.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
			assertThat(expensas.getBudgetedAmount()).isEqualByComparingTo("120000.00");
			assertThat(expensas.isManual()).isFalse();
			assertThat(expensas.getAccount()).isSameAs(pesos);
			assertThat(expensas.getConsolidatedAmount()).isNull();
			verify(entries, never()).save(any());
		}
	}

	@Nested
	class WhatIsStored {

		@Test
		void storesTheMovementWithTheUserTheEntryTheAccountTheDateAndTheAmountAtScaleTwo() {
			Registered result = service.register(USER_ID, ENTRY_ID,
					new NewMovement(LocalDate.parse("2026-11-05"), new BigDecimal("70000.5"), 42L, "  Primera parte "));

			ArgumentCaptor<Movement> saved = ArgumentCaptor.forClass(Movement.class);
			verify(movements).save(saved.capture());
			Movement m = saved.getValue();
			assertThat(m.getUserId()).isEqualTo(USER_ID);
			assertThat(m.getEntry()).isSameAs(expensas);
			assertThat(m.getAccount()).isSameAs(pesos);
			assertThat(m.getMovementDate()).isEqualTo(LocalDate.parse("2026-11-05"));
			assertThat(m.getAmount()).isEqualTo(new BigDecimal("70000.50"));
			assertThat(m.getNote()).isEqualTo("Primera parte");
			MovementRow row = result.movement();
			assertThat(row.entryId()).isEqualTo(ENTRY_ID);
			assertThat(row.accountName()).isEqualTo("Banco");
			assertThat(row.currency()).isEqualTo(Currency.ARS);
		}

		@Test
		void aBlankOrMissingNoteIsStoredAsNoNote() {
			service.register(USER_ID, ENTRY_ID,
					new NewMovement(LocalDate.parse("2026-11-05"), new BigDecimal("1.00"), 42L, "   "));
			service.register(USER_ID, ENTRY_ID,
					new NewMovement(LocalDate.parse("2026-11-05"), new BigDecimal("1.00"), 42L, null));

			assertThat(stored).extracting(Movement::getNote).containsExactly(null, null);
		}

		@Test
		void aMovementFromAnotherAccountOfTheSameCurrencyKeepsTheEntryAccount() {
			// S-02: la cuenta de la partida es solo la sugerida.
			Registered result = register("2026-11-05", "70000.00", 44);

			assertThat(stored.getFirst().getAccount()).isSameAs(otherPesos);
			assertThat(result.movement().accountId()).isEqualTo(44L);
			assertThat(result.entry().accountId()).isEqualTo(42L);
		}

		@Test
		void aDollarEntryAcceptsAnotherDollarAccount() {
			Account savings = account(45, "Caja de ahorro USD", Currency.USD, "2026-08-01");
			when(accounts.findByIdAndUserId(45L, USER_ID)).thenReturn(Optional.of(savings));
			BudgetEntry income = entry(november, EntryKind.INCOME, dollars, "500.00");

			Registered result = register("2026-11-05", "500.00", 45);

			assertThat(stored.getFirst().getAccount()).isSameAs(savings);
			assertThat(result.entry().currency()).isEqualTo(Currency.USD);
			assertThat(result.entry().amounts().pending()).isEqualByComparingTo("0.00");
			assertThat(income.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		}
	}

	@Nested
	class Errors {

		@Test
		void anEntryOfAnotherUserOrAMissingOneIsNotFoundAndNothingIsStored() {
			assertThatThrownBy(() -> service.register(OTHER_USER_ID, ENTRY_ID, movement("2026-11-05", "1.00", 42)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			assertThatThrownBy(() -> service.register(USER_ID, 999, movement("2026-11-05", "1.00", 42)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			verify(entries).findByIdAndUserIdWithDetails(ENTRY_ID, OTHER_USER_ID);
			verify(movements, never()).save(any());
			verify(accounts, never()).findByIdAndUserId(anyLong(), anyLong());
		}

		@Test
		void aConsolidatedEntryIsNotPending() {
			expensas.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertRejected(movement("2026-11-05", "1.00", 42), ErrorCode.ENTRY_NOT_PENDING);
		}

		@Test
		void anEntryOfAClosedPeriodIsPeriodClosedEvenThoughItIsConsolidatedToo() {
			november.setStatus(PeriodStatus.CLOSED);
			expensas.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertRejected(movement("2026-11-05", "1.00", 42), ErrorCode.PERIOD_CLOSED);
		}

		@Test
		void anAccountOfAnotherUserIsAFieldErrorNotANotFound() {
			when(accounts.findByIdAndUserId(77L, USER_ID)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> register("2026-11-05", "1.00", 77))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
						assertThat(e.field()).isEqualTo("accountId");
					});
			verify(accounts).findByIdAndUserId(77L, USER_ID);
			assertThat(stored).isEmpty();
		}

		@Test
		void anAccountInAnotherCurrencyIsACurrencyMismatchEvenIfItIsTheUsersOwn() {
			assertThatThrownBy(() -> register("2026-11-05", "1.00", 43))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH);
						assertThat(e.getMessage()).contains("ARS").contains("USD").contains("Caja en dólares");
					});
			assertThat(stored).isEmpty();
		}

		@Test
		void aDateAfterTodayIsOutOfRange() {
			assertThatThrownBy(() -> register("2026-11-21", "1.00", 42))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.DATE_OUT_OF_RANGE);
						assertThat(e.getMessage()).contains("hoy").contains("20/11/2026");
					});
			assertThat(stored).isEmpty();
		}

		@Test
		void aDateBeforeTheAccountOpeningIsOutOfRangeAndNamesTheAccountChosen() {
			// La apertura es de la cuenta del movimiento, no de la partida (S-02).
			Account recent = account(46, "Cuenta nueva", Currency.ARS, "2026-11-10");
			when(accounts.findByIdAndUserId(46L, USER_ID)).thenReturn(Optional.of(recent));

			register("2026-11-10", "1.00", 46);
			stored.clear();
			assertThatThrownBy(() -> register("2026-11-09", "1.00", 46))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.DATE_OUT_OF_RANGE);
						assertThat(e.getMessage()).contains("Cuenta nueva").contains("10/11/2026");
					});
			assertThat(stored).isEmpty();
		}

		@Test
		void aDateBeforeTheWindowOfTheEntryIsOutOfRange() {
			// Partida de noviembre, ventana de 10 días: el límite es el 22/10.
			period(YearMonth.of(2026, 10), 99, PeriodStatus.OPEN);
			register("2026-10-22", "1.00", 42);
			stored.clear();

			assertThatThrownBy(() -> register("2026-10-21", "1.00", 42))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.DATE_OUT_OF_RANGE);
						assertThat(e.getMessage()).contains("22/10/2026").contains("10 días");
					});
			assertThat(stored).isEmpty();
		}

		@Test
		void aDateInAClosedMonthIsPeriodClosedEvenInsideTheWindow() {
			// HU-20, criterio 4: la partida es de noviembre y octubre, dentro de la ventana, está cerrado.
			period(YearMonth.of(2026, 10), 99, PeriodStatus.CLOSED);

			assertThatThrownBy(() -> register("2026-10-25", "1.00", 42))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED);
						assertThat(e.getMessage()).contains("2026-10");
					});
			assertThat(stored).isEmpty();
		}

		@Test
		void aDateInAMonthBeforeTheStartPeriodIsOutOfRangeNotClosed() {
			// El período inicial del usuario es 2026-08: julio no existe para él. La cuenta se abrió antes (dato
			// inconsistente con RN-33, pero la regla tiene que decidir): fuera de rango, no cerrado (D-33).
			Account early = account(47, "Vieja", Currency.ARS, "2026-01-01");
			when(accounts.findByIdAndUserId(47L, USER_ID)).thenReturn(Optional.of(early));
			BudgetPeriod august = period(YearMonth.of(2026, 8), 98, PeriodStatus.OPEN);
			BudgetEntry augustEntry = entry(august, EntryKind.EXPENSE, pesos, "1000.00");
			when(periods.findByUserIdAndPeriodMonth(USER_ID, YearMonth.of(2026, 7))).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.register(USER_ID, ENTRY_ID, movement("2026-07-25", "1.00", 47)))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.DATE_OUT_OF_RANGE);
						assertThat(e.getMessage()).contains("2026-07").contains("período inicial");
					});
			assertThat(stored).isEmpty();
			assertThat(augustEntry.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		}

		@Test
		void anEntryBeyondTheWindowSaysWhenItWillAcceptMovements() {
			// Hoy 20/11: para febrero la ventana abre el 22/01/2027. Ninguna fecha sirve todavía.
			BudgetPeriod february = period(YearMonth.of(2027, 2), 101, PeriodStatus.OPEN);
			entry(february, EntryKind.INCOME, pesos, "900000.00");

			for (String date : List.of("2026-11-20", "2027-01-22")) {
				assertThatThrownBy(() -> register(date, "1.00", 42))
						.isInstanceOfSatisfying(BusinessException.class, e -> {
							assertThat(e.code()).isEqualTo(ErrorCode.DATE_OUT_OF_RANGE);
							assertThat(e.getMessage()).contains("Todavía no").contains("22/01/2027");
						});
			}
			assertThat(stored).isEmpty();
		}

		@Test
		void aRejectedRequestStoresNothingAndTouchesNoOtherRepositoryWrite() {
			assertRejected(movement("2026-11-21", "1.00", 42), ErrorCode.DATE_OUT_OF_RANGE);

			verify(entries, never()).save(any());
			verify(movements, never()).sumByEntry(anyLong(), anyLong());
		}
	}

	@Nested
	class OrderOfEvaluation {

		@Test
		void thePeriodOfTheEntryComesBeforeItsStatus() {
			november.setStatus(PeriodStatus.CLOSED);
			expensas.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertRejected(movement("2026-11-21", "1.00", 43), ErrorCode.PERIOD_CLOSED);
		}

		@Test
		void theStatusComesBeforeTheAccountAndTheDate() {
			expensas.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertRejected(movement("2026-11-21", "1.00", 77), ErrorCode.ENTRY_NOT_PENDING);
		}

		@Test
		void theAccountComesBeforeTheCurrencyAndTheDate() {
			assertThatThrownBy(() -> register("2026-11-21", "1.00", 77))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
		}

		@Test
		void theCurrencyComesBeforeTheDate() {
			assertRejected(movement("2026-11-21", "1.00", 43), ErrorCode.CURRENCY_MISMATCH);
		}

		@Test
		void outOfRangeComesBeforeAClosedMonth() {
			// El 15/09 está antes de la ventana (22/10) y septiembre está cerrado: se informa el rango (RN-21).
			period(YearMonth.of(2026, 9), 97, PeriodStatus.CLOSED);

			assertRejected(movement("2026-09-15", "1.00", 42), ErrorCode.DATE_OUT_OF_RANGE);
		}
	}

	@Nested
	class TheWindow {

		/** Sueldo de diciembre (ingreso en pesos), hoy 30/11 y noviembre abierto. */
		void decemberIncome(MovementService at) {
			service = at;
			BudgetPeriod december = period(YearMonth.of(2026, 12), 102, PeriodStatus.OPEN);
			period(NOVEMBER, 100, PeriodStatus.OPEN);
			entry(december, EntryKind.INCOME, pesos, "1200000.00");
		}

		@Test
		void theExampleOfRn21TheFirstDayIsTheTwentyFirstOfNovember() {
			decemberIncome(serviceAt("2026-11-30T15:00:00Z", 10));

			for (String date : List.of("2026-11-21", "2026-11-25", "2026-11-30")) {
				assertThat(register(date, "100.00", 42).movement().date()).isEqualTo(LocalDate.parse(date));
			}
			assertThatThrownBy(() -> register("2026-11-20", "100.00", 42))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.DATE_OUT_OF_RANGE));
			assertThat(stored).hasSize(3);
		}

		@Test
		void theWindowIsTheConfiguredOne() {
			decemberIncome(serviceAt("2026-11-30T15:00:00Z", 3));

			register("2026-11-28", "100.00", 42);
			assertThatThrownBy(() -> register("2026-11-27", "100.00", 42))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.DATE_OUT_OF_RANGE));
		}

		@Test
		void anAdvanceIncomeIsRejectedWhenItsMonthIsClosed() {
			decemberIncome(serviceAt("2026-11-30T15:00:00Z", 10));
			period(NOVEMBER, 100, PeriodStatus.CLOSED);

			assertThatThrownBy(() -> register("2026-11-25", "100.00", 42))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED));
			assertThat(stored).isEmpty();
		}
	}

	@Nested
	class Listing {

		@Test
		void listsTheMovementsOfTheEntryOfTheUserAsTheRepositoryOrdersThem() {
			register("2026-11-12", "50000.00", 44);
			register("2026-11-05", "70000.00", 42);
			when(movements.findByUserIdAndEntryIdWithAccount(USER_ID, ENTRY_ID))
					.thenReturn(List.of(stored.get(1), stored.get(0)));

			List<MovementRow> rows = service.list(USER_ID, ENTRY_ID);

			assertThat(rows).extracting(MovementRow::date)
					.containsExactly(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-12"));
			assertThat(rows).extracting(MovementRow::accountName).containsExactly("Banco", "Billetera");
			verify(movements).findByUserIdAndEntryIdWithAccount(USER_ID, ENTRY_ID);
		}

		@Test
		void anEntryWithoutMovementsGivesAnEmptyList() {
			assertThat(service.list(USER_ID, ENTRY_ID)).isEmpty();
		}

		@Test
		void aConsolidatedEntryAndAClosedPeriodCanStillBeRead() {
			november.setStatus(PeriodStatus.CLOSED);
			expensas.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertThat(service.list(USER_ID, ENTRY_ID)).isEmpty();
		}

		@Test
		void theEntryOfAnotherUserIsNotFoundAndItsMovementsAreNeverQueried() {
			assertThatThrownBy(() -> service.list(OTHER_USER_ID, ENTRY_ID))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			verify(movements, never()).findByUserIdAndEntryIdWithAccount(anyLong(), anyLong());
		}
	}
}
