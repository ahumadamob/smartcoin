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
import com.smartcoin.movement.service.MovementService.NewMovement;
import com.smartcoin.movement.service.MovementService.Registered;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-21, RN-22, RN-21, RN-01 y D-35: sin base de datos, con repositorios simulados y el reloj en el 20 de noviembre de
 * 2026. La partida es Expensas de noviembre (120.000,00), con dos movimientos: 70.000,00 el 05/11 y 50.000,00 el 12/11,
 * ambos en la cuenta Banco. El repositorio de movimientos en memoria suma de verdad.
 */
class MovementEditServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final long ENTRY_ID = 900;
	static final long FIRST = 5000;
	static final long SECOND = 5001;
	static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	AccountRepository accounts = mock(AccountRepository.class);
	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	List<Movement> stored = new ArrayList<>();
	MovementService service;

	Account pesos = account(42, "Banco", Currency.ARS, "2026-08-01");
	Account otherPesos = account(44, "Billetera", Currency.ARS, "2026-08-01");
	Account lateAccount = account(46, "Cuenta nueva", Currency.ARS, "2026-11-10");
	Account dollars = account(43, "Caja en dólares", Currency.USD, "2026-08-01");
	BudgetPeriod november;
	BudgetEntry expensas;

	@BeforeEach
	void setUp() {
		service = serviceAt(10);
		november = period(NOVEMBER, 100, PeriodStatus.OPEN);
		expensas = entry(november);
		for (Account a : List.of(pesos, otherPesos, lateAccount, dollars)) {
			when(accounts.findByIdAndUserId(a.getId(), USER_ID)).thenReturn(Optional.of(a));
		}
		stored.add(movement(FIRST, "2026-11-05", "70000.00", pesos));
		stored.add(movement(SECOND, "2026-11-12", "50000.00", pesos));
		when(movements.findByIdAndUserIdWithAccount(anyLong(), anyLong())).thenAnswer(call -> stored.stream()
				.filter(m -> m.getId().equals(call.getArgument(0)) && m.getUserId().equals(call.getArgument(1)))
				.findFirst());
		when(movements.save(any(Movement.class))).thenAnswer(call -> call.getArgument(0));
		when(movements.sumByEntry(anyLong(), anyLong())).thenAnswer(call -> stored.stream()
				.map(Movement::getAmount).reduce(BigDecimal::add).orElse(null));
		org.mockito.Mockito.doAnswer(call -> stored.remove(call.<Movement>getArgument(0))).when(movements)
				.delete(any(Movement.class));
	}

	MovementService serviceAt(int earlyDays) {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, earlyDays),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		return new MovementService(entries, movements, accounts, periods, properties,
				Clock.fixed(Instant.parse("2026-11-20T15:00:00Z"), ZONE));
	}

	BudgetPeriod period(YearMonth month, long id, PeriodStatus status) {
		BudgetPeriod p = BudgetPeriod.open(USER_ID, month);
		ReflectionTestUtils.setField(p, "id", id);
		p.setStatus(status);
		when(periods.findByUserIdAndPeriodMonth(USER_ID, month)).thenReturn(Optional.of(p));
		return p;
	}

	BudgetEntry entry(BudgetPeriod period) {
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", ENTRY_ID);
		e.setUserId(USER_ID);
		e.setPeriod(period);
		e.setOrigin(EntryOrigin.ONE_OFF);
		e.setName("Expensas");
		e.setKind(EntryKind.EXPENSE);
		e.setAccount(pesos);
		e.setDueDate(period.getPeriodMonth().atDay(10));
		e.setBudgetedAmount(new BigDecimal("120000.00"));
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

	Movement movement(long id, String date, String amount, Account account) {
		Movement m = new Movement();
		ReflectionTestUtils.setField(m, "id", id);
		m.setUserId(USER_ID);
		m.setEntry(expensas);
		m.setAccount(account);
		m.setMovementDate(LocalDate.parse(date));
		m.setAmount(new BigDecimal(amount));
		return m;
	}

	static NewMovement values(String date, String amount, long accountId) {
		return new NewMovement(LocalDate.parse(date), new BigDecimal(amount), accountId, null);
	}

	Registered update(long id, NewMovement values) {
		return service.update(USER_ID, id, values);
	}

	Movement first() {
		return stored.stream().filter(m -> m.getId() == FIRST).findFirst().orElseThrow();
	}

	void assertRejected(long id, NewMovement values, ErrorCode code) {
		assertThatThrownBy(() -> update(id, values))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));
		verify(movements, never()).save(any());
		assertThat(first().getAmount()).isEqualByComparingTo("70000.00");
		assertThat(first().getMovementDate()).isEqualTo(LocalDate.parse("2026-11-05"));
		assertThat(first().getAccount()).isSameAs(pesos);
	}

	void assertEntryUntouched() {
		assertThat(expensas.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(expensas.getBudgetedAmount()).isEqualByComparingTo("120000.00");
		assertThat(expensas.isManual()).isFalse();
		assertThat(expensas.getAccount()).isSameAs(pesos);
		assertThat(expensas.getConsolidatedAmount()).isNull();
		assertThat(expensas.getPeriod()).isSameAs(november);
		verify(entries, never()).save(any());
	}

	@Nested
	class Updating {

		@Test
		void changingTheAmountRecalculatesRealAndPending() {
			Registered result = update(FIRST, values("2026-11-05", "30000.00", 42));

			assertThat(result.movement().amount()).isEqualByComparingTo("30000.00");
			assertThat(result.entry().amounts().actual()).isEqualByComparingTo("80000.00");
			assertThat(result.entry().amounts().pending()).isEqualByComparingTo("40000.00");
			assertThat(result.entry().amounts().status()).isEqualTo(EntryStatus.PARTIAL);
			assertEntryUntouched();
		}

		@Test
		void changingTheAmountToMoreThanTheBudgetLeavesNothingPending() {
			Registered result = update(FIRST, values("2026-11-05", "100000.00", 42));

			assertThat(result.entry().amounts().actual()).isEqualByComparingTo("150000.00");
			assertThat(result.entry().amounts().pending()).isEqualByComparingTo("0.00");
			assertThat(result.entry().amounts().forecast()).isEqualByComparingTo("150000.00");
		}

		@Test
		void changingTheDateKeepsEverythingElse() {
			Registered result = update(FIRST, values("2026-11-07", "70000.00", 42));

			assertThat(first().getMovementDate()).isEqualTo(LocalDate.parse("2026-11-07"));
			assertThat(result.movement().date()).isEqualTo(LocalDate.parse("2026-11-07"));
			assertThat(first().getAmount()).isEqualByComparingTo("70000.00");
			assertThat(result.entry().amounts().actual()).isEqualByComparingTo("120000.00");
		}

		@Test
		void changingTheNoteTrimsItAndBlankMeansNoNote() {
			update(FIRST, new NewMovement(LocalDate.parse("2026-11-05"), new BigDecimal("70000.00"), 42L, "  Primera "));
			assertThat(first().getNote()).isEqualTo("Primera");

			update(FIRST, new NewMovement(LocalDate.parse("2026-11-05"), new BigDecimal("70000.00"), 42L, "   "));
			assertThat(first().getNote()).isNull();
		}

		@Test
		void movingToAnotherAccountOfTheSameCurrency() {
			Registered result = update(FIRST, values("2026-11-05", "70000.00", 44));

			assertThat(first().getAccount()).isSameAs(otherPesos);
			assertThat(result.movement().accountId()).isEqualTo(44L);
			assertThat(result.movement().accountName()).isEqualTo("Billetera");
			assertThat(result.entry().accountId()).isEqualTo(42L);
			assertEntryUntouched();
		}

		@Test
		void movingToAnAccountOfAnotherCurrencyIsRejected() {
			assertRejected(FIRST, values("2026-11-05", "70000.00", 43), ErrorCode.CURRENCY_MISMATCH);
		}

		@Test
		void aNewAccountThatDoesNotExistOrBelongsToAnotherUserIsAFieldError() {
			when(accounts.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> update(FIRST, values("2026-11-05", "70000.00", 99)))
					.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code())
							.isEqualTo(ErrorCode.VALIDATION_ERROR));
			verify(movements, never()).save(any());
		}

		@Test
		void aNewDateBeforeTheWindowIsOutOfRange() {
			// Ventana de 10 días: la fecha más temprana de noviembre es el 22/10.
			assertRejected(FIRST, values("2026-10-21", "70000.00", 42), ErrorCode.DATE_OUT_OF_RANGE);
		}

		@Test
		void theExactLimitOfTheWindowIsValid() {
			period(YearMonth.of(2026, 10), 99, PeriodStatus.OPEN);

			update(FIRST, values("2026-10-22", "70000.00", 42));

			assertThat(first().getMovementDate()).isEqualTo(LocalDate.parse("2026-10-22"));
		}

		@Test
		void aFutureDateIsOutOfRange() {
			assertRejected(FIRST, values("2026-11-21", "70000.00", 42), ErrorCode.DATE_OUT_OF_RANGE);
		}

		@Test
		void todayIsValid() {
			update(FIRST, values("2026-11-20", "70000.00", 42));

			assertThat(first().getMovementDate()).isEqualTo(LocalDate.parse("2026-11-20"));
		}

		@Test
		void aDateBeforeTheOpeningOfTheNewAccountIsOutOfRange() {
			// La cuenta nueva se abrió el 10/11: el 05/11 es válido para Banco y no para ella.
			assertRejected(FIRST, values("2026-11-05", "70000.00", 46), ErrorCode.DATE_OUT_OF_RANGE);
		}

		@Test
		void theOpeningDayOfTheNewAccountIsValid() {
			update(FIRST, values("2026-11-10", "70000.00", 46));

			assertThat(first().getAccount()).isSameAs(lateAccount);
		}

		@Test
		void aNewDateInAClosedMonthIsRejectedEvenInsideTheWindow() {
			// Octubre cerrado; el 25/10 está dentro de la ventana de noviembre.
			period(YearMonth.of(2026, 10), 99, PeriodStatus.CLOSED);

			assertRejected(FIRST, values("2026-10-25", "70000.00", 42), ErrorCode.PERIOD_CLOSED);
		}

		@Test
		void aDateOutOfRangeInAClosedMonthReportsTheRange() {
			period(YearMonth.of(2026, 10), 99, PeriodStatus.CLOSED);

			assertRejected(FIRST, values("2026-10-10", "70000.00", 42), ErrorCode.DATE_OUT_OF_RANGE);
		}

		@Test
		void aMovementDatedInAnOpenMonthBeforeTheEntryPeriodCanBeCorrected() {
			// Cobro anticipado: la partida es de noviembre y el movimiento, del 25/10 (octubre abierto).
			period(YearMonth.of(2026, 10), 99, PeriodStatus.OPEN);
			stored.getFirst().setMovementDate(LocalDate.parse("2026-10-25"));

			update(FIRST, values("2026-10-25", "65000.00", 42));

			assertThat(first().getAmount()).isEqualByComparingTo("65000.00");
		}

		@Test
		void theEntryIsNeverModified() {
			update(FIRST, values("2026-11-06", "10000.00", 44));
			update(SECOND, values("2026-11-07", "20000.00", 42));

			assertEntryUntouched();
			verify(movements, org.mockito.Mockito.times(2)).save(any());
		}

		@Test
		void theMovementStaysInItsEntry() {
			update(FIRST, values("2026-11-06", "10000.00", 44));

			assertThat(first().getEntry()).isSameAs(expensas);
		}
	}

	@Nested
	class NothingChanges {

		@Test
		void sendingTheSameValuesIsValidAndSavesNothing() {
			Registered result = update(FIRST, values("2026-11-05", "70000.00", 42));

			assertThat(result.movement().id()).isEqualTo(FIRST);
			assertThat(result.entry().amounts().actual()).isEqualByComparingTo("120000.00");
			verify(movements, never()).save(any());
		}

		@Test
		void sameValuesWithADifferentScaleOfTheAmountAreStillNoChange() {
			update(FIRST, values("2026-11-05", "70000", 42));

			verify(movements, never()).save(any());
		}

		@Test
		void withNothingChangedTheDateIsNotRevalidated() {
			// D-35: se redujo la ventana a 3 días; el 05/11 es anterior al 29/10 pero no cambió.
			service = serviceAt(0);
			stored.getFirst().setMovementDate(LocalDate.parse("2026-10-30"));

			update(FIRST, values("2026-10-30", "70000.00", 42));

			verify(movements, never()).save(any());
		}

		@Test
		void changingOnlyTheAmountOrTheNoteDoesNotRevalidateTheDate() {
			service = serviceAt(0);
			stored.getFirst().setMovementDate(LocalDate.parse("2026-10-30"));

			update(FIRST, values("2026-10-30", "71000.00", 42));
			update(FIRST, new NewMovement(LocalDate.parse("2026-10-30"), new BigDecimal("71000.00"), 42L, "nota"));

			assertThat(first().getAmount()).isEqualByComparingTo("71000.00");
			assertThat(first().getNote()).isEqualTo("nota");
		}

		@Test
		void changingTheDateRevalidatesItAgainstTheCurrentWindow() {
			service = serviceAt(0);
			stored.getFirst().setMovementDate(LocalDate.parse("2026-11-05"));

			assertRejected(FIRST, values("2026-10-31", "70000.00", 42), ErrorCode.DATE_OUT_OF_RANGE);
		}
	}

	@Nested
	class WhenTheMovementCannotBeChanged {

		@Test
		void aConsolidatedEntryIsRejected() {
			expensas.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertRejected(FIRST, values("2026-11-05", "30000.00", 42), ErrorCode.ENTRY_NOT_PENDING);
			assertThatThrownBy(() -> service.delete(USER_ID, FIRST))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING));
			assertThat(stored).hasSize(2);
		}

		@Test
		void aClosedEntryPeriodReportsThePeriodBeforeTheStatus() {
			november.setStatus(PeriodStatus.CLOSED);
			expensas.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertRejected(FIRST, values("2026-11-05", "30000.00", 42), ErrorCode.PERIOD_CLOSED);
			assertThatThrownBy(() -> service.delete(USER_ID, FIRST))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED));
		}

		@Test
		void aMovementWhoseCurrentDateIsInAClosedMonthCannotBeEdited() {
			period(YearMonth.of(2026, 10), 99, PeriodStatus.CLOSED);
			stored.getFirst().setMovementDate(LocalDate.parse("2026-10-25"));

			assertThatThrownBy(() -> update(FIRST, values("2026-11-05", "70000.00", 42)))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED);
						assertThat(e.getMessage()).contains("2026-10");
					});
			verify(movements, never()).save(any());
		}

		@Test
		void aMovementWhoseCurrentDateIsInAClosedMonthCannotBeDeleted() {
			period(YearMonth.of(2026, 10), 99, PeriodStatus.CLOSED);
			stored.getFirst().setMovementDate(LocalDate.parse("2026-10-25"));

			assertThatThrownBy(() -> service.delete(USER_ID, FIRST))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED));
			verify(movements, never()).delete(any());
			assertThat(stored).hasSize(2);
		}

		@Test
		void whenBothTheCurrentAndTheNewMonthAreClosedTheCurrentOneIsReportedFirst() {
			period(YearMonth.of(2026, 10), 99, PeriodStatus.CLOSED);
			stored.getFirst().setMovementDate(LocalDate.parse("2026-10-25"));

			assertThatThrownBy(() -> update(FIRST, values("2026-10-26", "70000.00", 42)))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED);
						assertThat(e.getMessage()).contains("no se puede corregir ni eliminar");
					});
		}

		@Test
		void theCurrentStateIsCheckedBeforeTheNewValues() {
			expensas.setStatus(StoredEntryStatus.CONSOLIDATED);

			// Moneda y fecha también están mal: se informa la partida.
			assertThatThrownBy(() -> update(FIRST, values("2030-01-01", "70000.00", 43)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING));
		}

		@Test
		void aMovementOfAnotherUserIsNotFoundWhenEditingAndWhenDeleting() {
			assertThatThrownBy(() -> service.update(OTHER_USER_ID, FIRST, values("2026-11-05", "1.00", 42)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			assertThatThrownBy(() -> service.delete(OTHER_USER_ID, FIRST))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			verify(movements, org.mockito.Mockito.times(2)).findByIdAndUserIdWithAccount(FIRST, OTHER_USER_ID);
			verify(movements, never()).save(any());
			verify(movements, never()).delete(any());
			assertThat(stored).hasSize(2);
		}

		@Test
		void aMovementThatDoesNotExistIsNotFound() {
			assertThatThrownBy(() -> service.delete(USER_ID, 123456))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
		}
	}

	@Nested
	class Deleting {

		@Test
		void deletingOneOfSeveralRecalculatesTheEntry() {
			service.delete(USER_ID, FIRST);

			assertThat(stored).extracting(Movement::getId).containsExactly(SECOND);
			assertEntryUntouched();
			BigDecimal total = movements.sumByEntry(USER_ID, ENTRY_ID);
			var amounts = PeriodViewService.row(expensas, total, LocalDate.parse("2026-11-20")).amounts();
			assertThat(amounts.actual()).isEqualByComparingTo("50000.00");
			assertThat(amounts.pending()).isEqualByComparingTo("70000.00");
			assertThat(amounts.status()).isEqualTo(EntryStatus.PARTIAL);
		}

		@Test
		void deletingTheLastOneLeavesTheEntryEstimatedWithoutSavingAnythingInIt() {
			service.delete(USER_ID, FIRST);
			service.delete(USER_ID, SECOND);

			assertThat(stored).isEmpty();
			var amounts = PeriodViewService.row(expensas, movements.sumByEntry(USER_ID, ENTRY_ID),
					LocalDate.parse("2026-11-20")).amounts();
			assertThat(amounts.status()).isEqualTo(EntryStatus.ESTIMATED);
			assertThat(amounts.actual()).isEqualByComparingTo("0.00");
			assertThat(amounts.pending()).isEqualByComparingTo("120000.00");
			assertEntryUntouched();
		}

		@Test
		void deletingNeverTouchesTheEntry() {
			service.delete(USER_ID, FIRST);

			verify(entries, never()).save(any());
			verify(entries, never()).delete(any());
		}
	}
}
