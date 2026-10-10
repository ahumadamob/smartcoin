package com.smartcoin.entry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.entry.service.EntryService.Changes;
import com.smartcoin.entry.service.EntryService.NewOneOff;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
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
 * HU-16, RN-18, RN-19, RN-01 y D-24: sin base de datos, con repositorios simulados y el reloj en el 8 de octubre de
 * 2026. El usuario tiene una cuenta en pesos, otra en dólares y la categoría Hogar; el período es noviembre de 2026.
 */
class EntryServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
	static final long ENTRY_ID = 900;

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	AccountRepository accounts = mock(AccountRepository.class);
	CategoryRepository categories = mock(CategoryRepository.class);
	EntryService service;

	Account pesos = account(42, "Banco", Currency.ARS);
	Account otherPesos = account(44, "Billetera", Currency.ARS);
	Account dollars = account(43, "Caja en dólares", Currency.USD);
	Category home = category(5, "Hogar");
	Category services = category(6, "Servicios");
	BudgetPeriod november;

	@BeforeEach
	void setUp() {
		// 12:00 en Mendoza (UTC−3) del 8 de octubre de 2026.
		service = new EntryService(periods, entries, movements, accounts, categories, mock(BudgetItemRepository.class),
				Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZONE));
		november = period(NOVEMBER, 100, PeriodStatus.OPEN);
		when(accounts.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(pesos));
		when(accounts.findByIdAndUserId(44L, USER_ID)).thenReturn(Optional.of(otherPesos));
		when(accounts.findByIdAndUserId(43L, USER_ID)).thenReturn(Optional.of(dollars));
		when(categories.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(home));
		when(categories.findByIdAndUserId(6L, USER_ID)).thenReturn(Optional.of(services));
	}

	BudgetPeriod period(YearMonth month, long id, PeriodStatus status) {
		BudgetPeriod p = BudgetPeriod.open(USER_ID, month);
		ReflectionTestUtils.setField(p, "id", id);
		p.setStatus(status);
		when(periods.findByUserIdAndPeriodMonth(USER_ID, month)).thenReturn(Optional.of(p));
		return p;
	}

	static Account account(long id, String name, Currency currency) {
		Account a = new Account();
		ReflectionTestUtils.setField(a, "id", id);
		a.setName(name);
		a.setCurrency(currency);
		return a;
	}

	static Category category(long id, String name) {
		Category c = new Category();
		ReflectionTestUtils.setField(c, "id", id);
		c.setName(name);
		return c;
	}

	static LocalDate date(String iso) {
		return LocalDate.parse(iso);
	}

	static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	@Nested
	class Create {

		NewOneOff values(String dueDate) {
			return new NewOneOff("  Service del auto ", EntryKind.EXPENSE, 42L, 5L, date(dueDate), amount("85000.5"));
		}

		@BeforeEach
		void saveAssignsAnId() {
			when(entries.save(any(BudgetEntry.class))).thenAnswer(call -> {
				BudgetEntry e = call.getArgument(0);
				ReflectionTestUtils.setField(e, "id", ENTRY_ID);
				return e;
			});
		}

		@Test
		void createsAOneOffEntryInTheOpenPeriodAndAnswersWithItsDerivedValues() {
			EntryRow row = service.createOneOff(USER_ID, NOVEMBER, values("2026-11-18"));

			ArgumentCaptor<BudgetEntry> saved = ArgumentCaptor.forClass(BudgetEntry.class);
			verify(entries).save(saved.capture());
			BudgetEntry entry = saved.getValue();
			assertThat(entry.getUserId()).isEqualTo(USER_ID);
			assertThat(entry.getPeriod()).isSameAs(november);
			assertThat(entry.getOrigin()).isEqualTo(EntryOrigin.ONE_OFF);
			assertThat(entry.getBudgetItem()).isNull();
			assertThat(entry.getName()).isEqualTo("Service del auto");
			assertThat(entry.getKind()).isEqualTo(EntryKind.EXPENSE);
			assertThat(entry.getCategory()).isSameAs(home);
			assertThat(entry.getAccount()).isSameAs(pesos);
			assertThat(entry.getDueDate()).isEqualTo(date("2026-11-18"));
			assertThat(entry.getBudgetedAmount()).isEqualTo(amount("85000.50"));
			assertThat(entry.getBudgetedAmount().scale()).isEqualTo(2);
			assertThat(entry.isManual()).isFalse();
			assertThat(entry.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
			assertThat(entry.getInstallmentNumber()).isNull();
			assertThat(entry.getSourceEntry()).isNull();

			assertThat(row.id()).isEqualTo(ENTRY_ID);
			assertThat(row.budgetItemId()).isNull();
			assertThat(row.origin()).isEqualTo(EntryOrigin.ONE_OFF);
			assertThat(row.name()).isEqualTo("Service del auto");
			assertThat(row.categoryId()).isEqualTo(5L);
			assertThat(row.categoryName()).isEqualTo("Hogar");
			assertThat(row.accountName()).isEqualTo("Banco");
			assertThat(row.currency()).isEqualTo(Currency.ARS);
			assertThat(row.amounts().status()).isEqualTo(EntryStatus.ESTIMATED);
			assertThat(row.amounts().actual()).isEqualByComparingTo("0");
			assertThat(row.amounts().pending()).isEqualByComparingTo("85000.50");
			assertThat(row.amounts().forecast()).isEqualByComparingTo("85000.50");
			assertThat(row.manual()).isFalse();
			assertThat(row.overdue()).isFalse();
		}

		@Test
		void categoryIsOptional() {
			NewOneOff values = new NewOneOff("Regalo", EntryKind.EXPENSE, 42L, null, date("2026-11-02"), amount("0"));

			EntryRow row = service.createOneOff(USER_ID, NOVEMBER, values);

			assertThat(row.categoryId()).isNull();
			assertThat(row.categoryName()).isNull();
			assertThat(row.budgetedAmount()).isEqualByComparingTo("0");
			verifyNoInteractions(categories);
		}

		@Test
		void incomeInADollarAccountHasTheCurrencyOfItsAccount() {
			NewOneOff values = new NewOneOff("Venta de bici", EntryKind.INCOME, 43L, null, date("2026-11-10"),
					amount("300.00"));

			EntryRow row = service.createOneOff(USER_ID, NOVEMBER, values);

			assertThat(row.kind()).isEqualTo(EntryKind.INCOME);
			assertThat(row.currency()).isEqualTo(Currency.USD);
		}

		@Test
		void aDueDateBeforeTodayIsAllowedAndTheEntryIsBornOverdue() {
			// Hoy es el 8/10/2026 y el período abierto es octubre: se carga algo que venció el 2.
			BudgetPeriod october = period(YearMonth.of(2026, 10), 99, PeriodStatus.OPEN);

			EntryRow row = service.createOneOff(USER_ID, YearMonth.of(2026, 10),
					new NewOneOff("Patente", EntryKind.EXPENSE, 42L, null, date("2026-10-02"), amount("1000.00")));

			assertThat(row.overdue()).isTrue();
			assertThat(row.amounts().status()).isEqualTo(EntryStatus.ESTIMATED);
			ArgumentCaptor<BudgetEntry> saved = ArgumentCaptor.forClass(BudgetEntry.class);
			verify(entries).save(saved.capture());
			assertThat(saved.getValue().getPeriod()).isSameAs(october);
		}

		@Test
		void aPastPeriodThatIsNotClosedYetAcceptsEntries() {
			// S-24: «abierto» es no cerrado, también un mes pasado.
			period(YearMonth.of(2026, 8), 97, PeriodStatus.OPEN);

			EntryRow row = service.createOneOff(USER_ID, YearMonth.of(2026, 8),
					new NewOneOff("Olvidado", EntryKind.EXPENSE, 42L, null, date("2026-08-20"), amount("10.00")));

			assertThat(row.overdue()).isTrue();
		}

		@Test
		void aPeriodThatDoesNotExistForTheUserIsNotFound() {
			assertThatThrownBy(() -> service.createOneOff(USER_ID, YearMonth.of(2030, 1), values("2030-01-10")))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			verify(entries, never()).save(any());
		}

		@Test
		void anotherUsersPeriodIsNotFoundBecauseTheLookupIsByUser() {
			assertThatThrownBy(() -> service.createOneOff(OTHER_USER_ID, NOVEMBER, values("2026-11-18")))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			verify(periods).findByUserIdAndPeriodMonth(OTHER_USER_ID, NOVEMBER);
			verify(entries, never()).save(any());
		}

		@Test
		void aClosedPeriodIsRejectedBeforeLookingAtTheBody() {
			november.setStatus(PeriodStatus.CLOSED);

			// El cuerpo también está mal (vencimiento y cuenta): el motivo que se informa es el período.
			NewOneOff bad = new NewOneOff("X", EntryKind.EXPENSE, 999L, null, date("2030-01-01"), amount("1.00"));

			assertThatThrownBy(() -> service.createOneOff(USER_ID, NOVEMBER, bad))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED);
						assertThat(e.getMessage()).contains("2026-11");
					});
			verifyNoInteractions(accounts, categories);
			verify(entries, never()).save(any());
		}

		@Test
		void aDueDateOutOfRangeIsAValidationErrorOnDueDateThatNamesTheRange() {
			assertThatThrownBy(() -> service.createOneOff(USER_ID, NOVEMBER, values("2026-09-30")))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
						assertThat(e.field()).isEqualTo("dueDate");
						assertThat(e.getMessage()).contains("01/10/2026").contains("30/11/2026");
					});
			assertThatThrownBy(() -> service.createOneOff(USER_ID, NOVEMBER, values("2026-12-01")))
					.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.field()).isEqualTo("dueDate"));
			verify(entries, never()).save(any());
		}

		@Test
		void theLimitsOfTheRangeAreAccepted() {
			assertThat(service.createOneOff(USER_ID, NOVEMBER, values("2026-10-01")).dueDate())
					.isEqualTo(date("2026-10-01"));
			assertThat(service.createOneOff(USER_ID, NOVEMBER, values("2026-11-30")).dueDate())
					.isEqualTo(date("2026-11-30"));
		}

		@Test
		void anAccountThatDoesNotExistOrIsAnotherUsersIsAValidationErrorOnAccountId() {
			NewOneOff foreign = new NewOneOff("X", EntryKind.EXPENSE, 77L, null, date("2026-11-18"), amount("1.00"));

			assertThatThrownBy(() -> service.createOneOff(USER_ID, NOVEMBER, foreign))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
						assertThat(e.field()).isEqualTo("accountId");
					});
			verify(accounts).findByIdAndUserId(77L, USER_ID);
			verify(entries, never()).save(any());
		}

		@Test
		void aCategoryThatDoesNotExistOrIsAnotherUsersIsAValidationErrorOnCategoryId() {
			NewOneOff foreign = new NewOneOff("X", EntryKind.EXPENSE, 42L, 77L, date("2026-11-18"), amount("1.00"));

			assertThatThrownBy(() -> service.createOneOff(USER_ID, NOVEMBER, foreign))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
						assertThat(e.field()).isEqualTo("categoryId");
					});
			verify(categories).findByIdAndUserId(77L, USER_ID);
			verify(entries, never()).save(any());
		}

		@Test
		void itSavesOnlyTheNewEntry() {
			service.createOneOff(USER_ID, NOVEMBER, values("2026-11-18"));

			// Ni se copia a otros períodos ni se toca otra partida (RN-19): un solo save y ninguna otra consulta.
			verify(entries).save(any(BudgetEntry.class));
			verify(entries, never()).saveAll(anyCollection());
			verifyNoMoreInteractions(entries);
			verifyNoInteractions(movements);
		}
	}

	@Nested
	class Update {

		BudgetEntry entry;
		long nextId = 1;

		@BeforeEach
		void aPendingOneOffEntryOfNovember() {
			entry = oneOff();
			when(entries.findByIdAndUserIdWithDetails(ENTRY_ID, USER_ID)).thenReturn(Optional.of(entry));
		}

		BudgetEntry oneOff() {
			BudgetEntry e = new BudgetEntry();
			ReflectionTestUtils.setField(e, "id", ENTRY_ID);
			e.setUserId(USER_ID);
			e.setPeriod(november);
			e.setOrigin(EntryOrigin.ONE_OFF);
			e.setName("Service del auto");
			e.setKind(EntryKind.EXPENSE);
			e.setCategory(home);
			e.setAccount(pesos);
			e.setDueDate(date("2026-11-18"));
			e.setBudgetedAmount(amount("45000.00"));
			e.setManual(false);
			e.setStatus(StoredEntryStatus.PENDING);
			return e;
		}

		BudgetEntry recurring() {
			BudgetItem item = new BudgetItem();
			ReflectionTestUtils.setField(item, "id", 31L);
			item.setName("Luz");
			item.setCategory(services);
			BudgetEntry e = oneOff();
			e.setOrigin(EntryOrigin.RECURRING);
			e.setBudgetItem(item);
			e.setName(null);
			e.setCategory(null);
			when(entries.findByIdAndUserIdWithDetails(ENTRY_ID, USER_ID)).thenReturn(Optional.of(e));
			return e;
		}

		Changes nothing() {
			return new Changes(null, null, null, null, false, null, null);
		}

		Changes only(String name, Long accountId, Long categoryId, boolean clear, String dueDate, String budgeted) {
			return new Changes(name, null, accountId, categoryId, clear, dueDate == null ? null : date(dueDate),
					budgeted == null ? null : amount(budgeted));
		}

		void assertUnchanged() {
			assertThat(entry.getName()).isEqualTo("Service del auto");
			assertThat(entry.getKind()).isEqualTo(EntryKind.EXPENSE);
			assertThat(entry.getCategory()).isSameAs(home);
			assertThat(entry.getAccount()).isSameAs(pesos);
			assertThat(entry.getDueDate()).isEqualTo(date("2026-11-18"));
			assertThat(entry.getBudgetedAmount()).isEqualByComparingTo("45000.00");
			assertThat(entry.isManual()).isFalse();
		}

		@Test
		void changesOnlyTheName() {
			EntryRow row = service.update(USER_ID, ENTRY_ID, only("  Cambio de aceite ", null, null, false, null, null));

			assertThat(entry.getName()).isEqualTo("Cambio de aceite");
			assertThat(entry.getCategory()).isSameAs(home);
			assertThat(entry.getAccount()).isSameAs(pesos);
			assertThat(entry.getDueDate()).isEqualTo(date("2026-11-18"));
			assertThat(entry.getBudgetedAmount()).isEqualByComparingTo("45000.00");
			assertThat(row.name()).isEqualTo("Cambio de aceite");
		}

		@Test
		void changesOnlyTheCategory() {
			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, 6L, false, null, null));

			assertThat(entry.getCategory()).isSameAs(services);
			assertThat(entry.getName()).isEqualTo("Service del auto");
			assertThat(row.categoryName()).isEqualTo("Servicios");
		}

		@Test
		void clearsTheCategoryOnlyWhenAskedTo() {
			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, null, true, null, null));

			assertThat(entry.getCategory()).isNull();
			assertThat(row.categoryId()).isNull();
			assertThat(row.categoryName()).isNull();
			assertThat(entry.getName()).isEqualTo("Service del auto");
		}

		@Test
		void notSendingTheCategoryKeepsIt() {
			service.update(USER_ID, ENTRY_ID, only("Otro nombre", null, null, false, null, null));

			assertThat(entry.getCategory()).isSameAs(home);
		}

		@Test
		void clearingTheCategoryOfAnEntryWithoutOneIsNotAnError() {
			entry.setCategory(null);

			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, null, true, null, null));

			assertThat(row.categoryId()).isNull();
		}

		@Test
		void changesOnlyTheDueDate() {
			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, null, false, "2026-10-31", null));

			assertThat(entry.getDueDate()).isEqualTo(date("2026-10-31"));
			assertThat(row.dueDate()).isEqualTo(date("2026-10-31"));
			assertThat(entry.getName()).isEqualTo("Service del auto");
		}

		@Test
		void changesOnlyTheBudgetedAmountAndDoesNotMarkTheEntryAsEdited() {
			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "52000.5"));

			assertThat(entry.getBudgetedAmount()).isEqualTo(amount("52000.50"));
			assertThat(entry.getBudgetedAmount().scale()).isEqualTo(2);
			assertThat(entry.isManual()).isFalse();
			assertThat(row.manual()).isFalse();
			assertThat(row.amounts().pending()).isEqualByComparingTo("52000.50");
		}

		@Test
		void changesOnlyTheAccountToAnotherOneOfTheSameCurrencyWithoutLookingForMovements() {
			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, 44L, null, false, null, null));

			assertThat(entry.getAccount()).isSameAs(otherPesos);
			assertThat(row.accountName()).isEqualTo("Billetera");
			assertThat(row.currency()).isEqualTo(Currency.ARS);
			verify(movements, never()).findEntryIdsWithMovements(anyLong(), anyCollection());
		}

		@Test
		void changesEveryFieldAtOnce() {
			Changes all = new Changes("Nuevo", EntryKind.EXPENSE, 44L, 6L, false, date("2026-11-30"), amount("1.00"));

			EntryRow row = service.update(USER_ID, ENTRY_ID, all);

			assertThat(row.name()).isEqualTo("Nuevo");
			assertThat(row.accountId()).isEqualTo(44L);
			assertThat(row.categoryId()).isEqualTo(6L);
			assertThat(row.dueDate()).isEqualTo(date("2026-11-30"));
			assertThat(row.budgetedAmount()).isEqualByComparingTo("1.00");
			assertThat(row.manual()).isFalse();
		}

		@Test
		void anEmptyChangeIsANoOp() {
			EntryRow row = service.update(USER_ID, ENTRY_ID, nothing());

			assertUnchanged();
			assertThat(row.name()).isEqualTo("Service del auto");
		}

		@Test
		void sendingTheSameValuesIsNotAChange() {
			Changes same = new Changes("Service del auto", EntryKind.EXPENSE, 42L, 5L, false, date("2026-11-18"),
					amount("45000.00"));

			service.update(USER_ID, ENTRY_ID, same);

			assertUnchanged();
		}

		@Test
		void theKindIsNotEditable() {
			Changes toIncome = new Changes(null, EntryKind.INCOME, null, null, false, null, null);

			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, toIncome))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_EDITABLE));
			assertUnchanged();
		}

		@Test
		void aCategoryAndClearingItInTheSameRequestIsAValidationErrorOnClearCategory() {
			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only(null, null, 6L, true, null, null)))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
						assertThat(e.field()).isEqualTo("clearCategory");
					});
			assertUnchanged();
		}

		@Test
		void anEntryOfAnotherUserIsNotFound() {
			assertThatThrownBy(() -> service.update(OTHER_USER_ID, ENTRY_ID, only("X", null, null, false, null, null)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			verify(entries).findByIdAndUserIdWithDetails(ENTRY_ID, OTHER_USER_ID);
			assertUnchanged();
		}

		@Test
		void anEntryThatDoesNotExistIsNotFound() {
			assertThatThrownBy(() -> service.update(USER_ID, 12345L, nothing()))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
		}

		@Test
		void aConsolidatedEntryIsNotPending() {
			entry.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only("X", null, null, false, null, null)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING));
			assertThat(entry.getName()).isEqualTo("Service del auto");
		}

		@Test
		void anEntryOfAClosedPeriodIsRejectedWithPeriodClosedEvenThoughItIsConsolidated() {
			november.setStatus(PeriodStatus.CLOSED);
			entry.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only("X", null, null, false, null, null)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED));
			assertUnchanged();
		}

		@Test
		void aDueDateOutOfTheRangeOfTheEntrysPeriodIsAValidationErrorOnDueDate() {
			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only(null, null, null, false, "2026-12-01", null)))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
						assertThat(e.field()).isEqualTo("dueDate");
						assertThat(e.getMessage()).contains("01/10/2026").contains("30/11/2026");
					});
			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only(null, null, null, false, "2026-09-30", null)))
					.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.field()).isEqualTo("dueDate"));
			assertUnchanged();
		}

		@Test
		void anAccountOrCategoryThatIsNotTheUsersIsAValidationErrorInItsField() {
			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only(null, 77L, null, false, null, null)))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
						assertThat(e.field()).isEqualTo("accountId");
					});
			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only(null, null, 77L, false, null, null)))
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
						assertThat(e.field()).isEqualTo("categoryId");
					});
			verify(accounts).findByIdAndUserId(77L, USER_ID);
			verify(categories).findByIdAndUserId(77L, USER_ID);
			assertUnchanged();
		}

		@Test
		void withoutMovementsTheAccountCanChangeToAnotherCurrency() {
			when(movements.findEntryIdsWithMovements(USER_ID, List.of(ENTRY_ID))).thenReturn(List.of());

			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, 43L, null, false, null, null));

			assertThat(entry.getAccount()).isSameAs(dollars);
			assertThat(row.currency()).isEqualTo(Currency.USD);
			assertThat(row.accountName()).isEqualTo("Caja en dólares");
		}

		@Test
		void withMovementsTheAccountCannotChangeToAnotherCurrency() {
			when(movements.findEntryIdsWithMovements(USER_ID, List.of(ENTRY_ID))).thenReturn(List.of(ENTRY_ID));

			// También cambia el nombre en el mismo pedido: al rechazarse no cambia nada.
			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only("Otro", 43L, null, false, null, null)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
			assertUnchanged();
		}

		@Test
		void withMovementsTheAccountCanChangeToAnotherOneOfTheSameCurrency() {
			when(movements.findEntryIdsWithMovements(USER_ID, List.of(ENTRY_ID))).thenReturn(List.of(ENTRY_ID));
			when(movements.sumByEntry(USER_ID, ENTRY_ID)).thenReturn(amount("20000.00"));

			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, 44L, null, false, null, null));

			assertThat(entry.getAccount()).isSameAs(otherPesos);
			// Una partida Parcial se edita igual: sigue pendiente, y la respuesta trae su real.
			assertThat(row.amounts().status()).isEqualTo(EntryStatus.PARTIAL);
			assertThat(row.amounts().actual()).isEqualByComparingTo("20000.00");
			assertThat(row.amounts().pending()).isEqualByComparingTo("25000.00");
		}

		@Test
		void sendingTheCurrentAccountOfAnEntryWithMovementsIsNotAChange() {
			service.update(USER_ID, ENTRY_ID, only(null, 42L, null, false, null, null));

			verify(movements, never()).findEntryIdsWithMovements(anyLong(), anyCollection());
			assertUnchanged();
		}

		@Test
		void aRecurringEntryDoesNotAcceptChangesBecauseItsDataComesFromTheBudgetItem() {
			BudgetEntry recurring = recurring();

			for (Changes changes : List.of(
					only("Otro", null, null, false, null, null),
					only(null, 44L, null, false, null, null),
					only(null, null, 5L, false, null, null),
					only(null, null, null, true, null, null),
					only(null, null, null, false, "2026-11-20", null),
					new Changes(null, EntryKind.INCOME, null, null, false, null, null))) {
				assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, changes))
						.isInstanceOfSatisfying(BusinessException.class,
								e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_EDITABLE));
			}
			assertThat(recurring.isManual()).isFalse();
			assertThat(recurring.getBudgetedAmount()).isEqualByComparingTo("45000.00");
			assertThat(recurring.getAccount()).isSameAs(pesos);
		}

		@Test
		void changingTheBudgetedAmountOfARecurringEntryMarksItAsEditedAndTouchesNothingElse() {
			BudgetEntry recurring = recurring();
			recurring.setBudgetedAmount(amount("180000.00"));
			recurring.getBudgetItem().setCurrentAmount(amount("180000.00"));

			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "240000.00"));

			assertThat(recurring.getBudgetedAmount()).isEqualByComparingTo("240000.00");
			assertThat(recurring.isManual()).isTrue();
			assertThat(row.budgetedAmount()).isEqualByComparingTo("240000.00");
			assertThat(row.manual()).isTrue();
			assertThat(row.amounts().pending()).isEqualByComparingTo("240000.00");
			// Sin Concepto de por medio: ni se lo lee ni se lo guarda, y no se toca ninguna otra partida (D-11).
			assertThat(recurring.getBudgetItem().getCurrentAmount()).isEqualByComparingTo("180000.00");
			verify(entries).findByIdAndUserIdWithDetails(ENTRY_ID, USER_ID);
			verifyNoMoreInteractions(entries);
		}

		@Test
		void aBudgetedAmountOfZeroIsAccepted() {
			BudgetEntry recurring = recurring();

			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "0.00"));

			assertThat(recurring.getBudgetedAmount()).isEqualByComparingTo("0.00");
			assertThat(recurring.isManual()).isTrue();
			assertThat(row.amounts().pending()).isEqualByComparingTo("0.00");
		}

		@Test
		void aBudgetedAmountBelowWhatWasAlreadyPaidLeavesNothingPending() {
			BudgetEntry recurring = recurring();
			when(movements.sumByEntry(USER_ID, ENTRY_ID)).thenReturn(amount("30000.00"));

			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "20000.00"));

			assertThat(recurring.isManual()).isTrue();
			assertThat(row.amounts().actual()).isEqualByComparingTo("30000.00");
			assertThat(row.amounts().pending()).isEqualByComparingTo("0.00");
			assertThat(row.amounts().forecast()).isEqualByComparingTo("30000.00");
		}

		@Test
		void aPartialRecurringEntryRecalculatesItsPending() {
			BudgetEntry recurring = recurring();
			when(movements.sumByEntry(USER_ID, ENTRY_ID)).thenReturn(amount("10000.00"));

			EntryRow row = service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "60000.00"));

			assertThat(recurring.isManual()).isTrue();
			assertThat(row.amounts().pending()).isEqualByComparingTo("50000.00");
			assertThat(row.amounts().forecast()).isEqualByComparingTo("60000.00");
		}

		@Test
		void sendingTheSameBudgetedAmountOfARecurringEntryDoesNotMarkIt() {
			BudgetEntry recurring = recurring();

			service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "45000.00"));

			assertThat(recurring.isManual()).isFalse();
		}

		@Test
		void anEditedRecurringEntryStaysEditedWhenItGoesBackToItsOriginalAmount() {
			BudgetEntry recurring = recurring();

			service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "50000.00"));
			service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "45000.00"));

			assertThat(recurring.getBudgetedAmount()).isEqualByComparingTo("45000.00");
			assertThat(recurring.isManual()).isTrue();
		}

		@Test
		void aRequestThatMixesTheAmountWithAnotherChangeIsRejectedAsAWhole() {
			BudgetEntry recurring = recurring();

			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID,
					only(null, null, null, false, "2026-11-20", "50000.00")))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_EDITABLE));

			assertThat(recurring.getBudgetedAmount()).isEqualByComparingTo("45000.00");
			assertThat(recurring.isManual()).isFalse();
			assertThat(recurring.getDueDate()).isEqualTo(date("2026-11-18"));
		}

		@Test
		void aConsolidatedRecurringEntryIsNotPendingAndStaysUntouched() {
			BudgetEntry recurring = recurring();
			recurring.setStatus(StoredEntryStatus.CONSOLIDATED);

			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "1.00")))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING));
			assertThat(recurring.isManual()).isFalse();
			assertThat(recurring.getBudgetedAmount()).isEqualByComparingTo("45000.00");
		}

		@Test
		void aRecurringEntryOfAClosedPeriodIsRejectedWithPeriodClosed() {
			BudgetEntry recurring = recurring();
			november.setStatus(PeriodStatus.CLOSED);

			assertThatThrownBy(() -> service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "1.00")))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED));
			assertThat(recurring.isManual()).isFalse();
		}

		@Test
		void aRecurringEntryOfAnotherUserIsNotFound() {
			when(entries.findByIdAndUserIdWithDetails(ENTRY_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.update(OTHER_USER_ID, ENTRY_ID,
					only(null, null, null, false, null, "1.00")))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
		}

		@Test
		void changingTheBudgetedAmountOfAnEntryWithoutBudgetItemNeverMarksIt() {
			service.update(USER_ID, ENTRY_ID, only(null, null, null, false, null, "99000.00"));

			assertThat(entry.getBudgetedAmount()).isEqualByComparingTo("99000.00");
			assertThat(entry.isManual()).isFalse();
		}

		@Test
		void aRecurringEntryAcceptsAnUnchangedRequestAndShowsTheBudgetItemsData() {
			recurring();
			// Mismo nombre y categoría del Concepto, misma cuenta, vencimiento y monto: no es un cambio.
			Changes same = new Changes("Luz", EntryKind.EXPENSE, 42L, 6L, false, date("2026-11-18"),
					amount("45000.00"));

			EntryRow row = service.update(USER_ID, ENTRY_ID, same);

			assertThat(row.name()).isEqualTo("Luz");
			assertThat(row.categoryName()).isEqualTo("Servicios");
			assertThat(row.budgetItemId()).isEqualTo(31L);
		}

		@Test
		void carriedOverAndClosingDifferenceEntriesAreEditedLikeAnyEntryWithoutBudgetItem() {
			BudgetEntry source = oneOff();
			ReflectionTestUtils.setField(source, "id", 800L);
			entry.setOrigin(EntryOrigin.CARRIED_OVER);
			entry.setSourceEntry(source);

			EntryRow row = service.update(USER_ID, ENTRY_ID, only("Saldo pendiente: Luz", null, null, false, null,
					"20000.00"));

			assertThat(row.origin()).isEqualTo(EntryOrigin.CARRIED_OVER);
			assertThat(row.name()).isEqualTo("Saldo pendiente: Luz");
			assertThat(entry.getSourceEntry()).isSameAs(source);
			assertThat(entry.isManual()).isFalse();
		}

		@Test
		void itOnlyTouchesTheEditedEntry() {
			service.update(USER_ID, ENTRY_ID, only("Otro", 44L, 6L, false, "2026-11-20", "10.00"));

			// Una sola lectura, y no guarda ni consulta otras partidas ni Conceptos (D-11, RN-18).
			verify(entries).findByIdAndUserIdWithDetails(ENTRY_ID, USER_ID);
			verify(entries, never()).save(any());
			verify(entries, never()).saveAll(anyCollection());
			verifyNoMoreInteractions(entries);
		}

		@Test
		void everyLookupCarriesTheCurrentUser() {
			service.update(USER_ID, ENTRY_ID, only("Otro", 44L, 6L, false, null, null));

			verify(entries).findByIdAndUserIdWithDetails(ENTRY_ID, USER_ID);
			verify(accounts).findByIdAndUserId(44L, USER_ID);
			verify(categories).findByIdAndUserId(6L, USER_ID);
			verify(movements).sumByEntry(USER_ID, ENTRY_ID);
		}
	}
}
