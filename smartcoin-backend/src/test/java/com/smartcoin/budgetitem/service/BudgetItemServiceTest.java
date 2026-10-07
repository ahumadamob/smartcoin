package com.smartcoin.budgetitem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.BudgetItemValues;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.budgetitem.service.BudgetItemService.Created;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-10, RN-10 a RN-13 y RN-01: sin base de datos, con repositorios simulados y un reloj fijo. El generador es el
 * real, sobre los repositorios simulados, para verificar las partidas que crea el alta.
 */
@ExtendWith(MockitoExtension.class)
class BudgetItemServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long ACCOUNT_ID = 42;
	static final long CATEGORY_ID = 5;
	/** Con el reloj en octubre de 2026 y 24 meses de horizonte. */
	static final YearMonth CURRENT = YearMonth.of(2026, 10);
	static final YearMonth HORIZON = YearMonth.of(2028, 10);
	static final YearMonth USER_START = YearMonth.of(2026, 8);

	@Mock
	BudgetItemRepository items;
	@Mock
	AccountRepository accounts;
	@Mock
	CategoryRepository categories;
	@Mock
	BudgetPeriodRepository periods;
	@Mock
	BudgetEntryRepository entries;
	@Mock
	UserRepository users;
	@Mock
	HorizonService horizon;

	BudgetItemService service;
	User user;
	Account account;
	Category category;

	@BeforeEach
	void setUp() {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, 10),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		Clock clock = Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"), ZONE);
		service = new BudgetItemService(items, accounts, categories, periods, users, horizon,
				new EntryGenerator(periods, entries), properties, clock);

		user = new User();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		user.setStartPeriod(USER_START);
		account = new Account();
		ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
		account.setUserId(USER_ID);
		account.setCurrency(Currency.ARS);
		category = new Category();
		ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
		category.setUserId(USER_ID);

		lenient().when(users.findById(USER_ID)).thenReturn(Optional.of(user));
		lenient().when(accounts.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
		lenient().when(categories.findByIdAndUserId(CATEGORY_ID, USER_ID)).thenReturn(Optional.of(category));
		lenient().when(periods.findFirstByUserIdAndStatusOrderByPeriodMonthDesc(USER_ID, PeriodStatus.CLOSED))
				.thenReturn(Optional.empty());
		lenient().when(periods.findByUserIdAndPeriodMonthIn(eq(USER_ID), anyCollection()))
				.thenAnswer(call -> call.<Collection<YearMonth>>getArgument(1).stream()
						.map(month -> BudgetPeriod.open(USER_ID, month)).toList());
		lenient().when(items.save(any(BudgetItem.class))).thenAnswer(call -> call.getArgument(0));
		lenient().when(entries.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));
	}

	private static YearMonth ym(String text) {
		return YearMonth.parse(text);
	}

	/** Un Concepto mensual sin fin: "Monotributo", gasto, vence el 20, 85.000,00. */
	private static BudgetItemValues monthly(String start) {
		return values(Periodicity.MONTHLY, 20, 0, start, null);
	}

	private static BudgetItemValues values(Periodicity periodicity, int dueDay, int offset, String start, String end) {
		return new BudgetItemValues("Monotributo", EntryKind.EXPENSE, ACCOUNT_ID, CATEGORY_ID, periodicity, dueDay,
				offset, ym(start), end == null ? null : ym(end), EstimationRule.LAST_VALUE, new BigDecimal("85000.00"));
	}

	private static List<YearMonth> periodsOf(Created created) {
		return created.entries().stream().map(entry -> entry.getPeriod().getPeriodMonth()).toList();
	}

	private static List<LocalDate> dueDatesOf(Created created) {
		return created.entries().stream().map(BudgetEntry::getDueDate).toList();
	}

	private static BusinessException rejected(Runnable call) {
		return catchThrowableOfType(BusinessException.class, call::run);
	}

	// --- El Concepto ---

	@Test
	void savesTheItemWithItsDataAndTheUserOfTheToken() {
		BudgetItemValues values = new BudgetItemValues("  Sueldo A ", EntryKind.INCOME, ACCOUNT_ID, CATEGORY_ID,
				Periodicity.MONTHLY, 25, -1, ym("2026-11"), ym("2030-01"), EstimationRule.AVERAGE_LAST_3,
				new BigDecimal("1200000.5"));

		BudgetItem item = service.create(USER_ID, values).item();

		verify(items).save(item);
		assertThat(item.getUserId()).isEqualTo(USER_ID);
		assertThat(item.getName()).isEqualTo("Sueldo A");
		assertThat(item.getKind()).isEqualTo(EntryKind.INCOME);
		assertThat(item.getDefaultAccount()).isSameAs(account);
		assertThat(item.getCategory()).isSameAs(category);
		assertThat(item.getPeriodicity()).isEqualTo(Periodicity.MONTHLY);
		assertThat(item.getDueDay()).isEqualTo(25);
		assertThat(item.getDueMonthOffset()).isEqualTo(-1);
		assertThat(item.getStartPeriod()).isEqualTo(ym("2026-11"));
		assertThat(item.getEndPeriod()).isEqualTo(ym("2030-01"));
		assertThat(item.getEstimationRule()).isEqualTo(EstimationRule.AVERAGE_LAST_3);
		assertThat(item.getCurrentAmount()).isEqualTo(new BigDecimal("1200000.50"));
		assertThat(item.getInstallmentsTotal()).isNull();
		assertThat(item.getFirstInstallmentNumber()).isNull();
	}

	@Test
	void theCategoryIsOptional() {
		BudgetItemValues values = new BudgetItemValues("Alquiler", EntryKind.EXPENSE, ACCOUNT_ID, null,
				Periodicity.MONTHLY, 10, 0, CURRENT, null, EstimationRule.LAST_VALUE, new BigDecimal("450000.00"));

		Created created = service.create(USER_ID, values);

		assertThat(created.item().getCategory()).isNull();
		assertThat(created.entries()).hasSize(25);
		verifyNoInteractions(categories);
	}

	@Test
	void aCurrentAmountOfZeroIsAccepted() {
		BudgetItemValues values = new BudgetItemValues("Aguinaldo", EntryKind.INCOME, ACCOUNT_ID, null,
				Periodicity.SEMIANNUAL, 30, 0, ym("2026-12"), null, EstimationRule.LAST_VALUE, BigDecimal.ZERO);

		Created created = service.create(USER_ID, values);

		assertThat(created.item().getCurrentAmount()).isEqualByComparingTo("0");
		assertThat(created.entries()).allSatisfy(
				entry -> assertThat(entry.getBudgetedAmount()).isEqualByComparingTo("0"));
	}

	// --- Las partidas generadas (RN-13) ---

	@Test
	void aMonthlyItemGeneratesOneEntryPerPeriodFromTheStartToTheHorizon() {
		Created created = service.create(USER_ID, monthly("2026-10"));

		assertThat(created.entries()).hasSize(25);
		assertThat(periodsOf(created).getFirst()).isEqualTo(ym("2026-10"));
		assertThat(periodsOf(created).getLast()).isEqualTo(HORIZON);
		assertThat(periodsOf(created)).doesNotHaveDuplicates().isSorted();
		assertThat(dueDatesOf(created).getFirst()).isEqualTo(LocalDate.of(2026, 10, 20));
		assertThat(dueDatesOf(created).getLast()).isEqualTo(LocalDate.of(2028, 10, 20));
		assertThat(created.item().getGeneratedUntil()).isEqualTo(HORIZON);
	}

	@Test
	void everyEntryIsRecurringPendingNotManualWithTheKindAccountAndAmountOfTheItem() {
		Created created = service.create(USER_ID, monthly("2026-10"));

		assertThat(created.entries()).allSatisfy(entry -> {
			assertThat(entry.getUserId()).isEqualTo(USER_ID);
			assertThat(entry.getBudgetItem()).isSameAs(created.item());
			assertThat(entry.getOrigin()).isEqualTo(EntryOrigin.RECURRING);
			assertThat(entry.getKind()).isEqualTo(EntryKind.EXPENSE);
			assertThat(entry.getAccount()).isSameAs(account);
			assertThat(entry.getBudgetedAmount()).isEqualTo(new BigDecimal("85000.00"));
			assertThat(entry.isManual()).isFalse();
			assertThat(entry.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
			assertThat(entry.getConsolidatedAmount()).isNull();
			assertThat(entry.getConsolidatedAt()).isNull();
			assertThat(entry.getInstallmentNumber()).isNull();
			// Las recurrentes muestran el nombre y la categoría del Concepto.
			assertThat(entry.getName()).isNull();
			assertThat(entry.getCategory()).isNull();
			assertThat(entry.getPeriod().getUserId()).isEqualTo(USER_ID);
		});
	}

	@Test
	void theDueDateFollowsTheDueDayAndTheLengthOfEachMonth() {
		Created created = service.create(USER_ID, values(Periodicity.MONTHLY, 31, 0, "2026-11", "2028-02"));

		assertThat(dueDatesOf(created)).startsWith(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31),
				LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28));
		assertThat(dueDatesOf(created).getLast()).isEqualTo(LocalDate.of(2028, 2, 29));
	}

	@Test
	void withOffsetTheEntriesAreDueTheMonthBeforeTheirPeriod() {
		Created created = service.create(USER_ID, values(Periodicity.MONTHLY, 30, -1, "2026-12", "2027-03"));

		assertThat(periodsOf(created)).containsExactly(ym("2026-12"), ym("2027-01"), ym("2027-02"), ym("2027-03"));
		assertThat(dueDatesOf(created)).containsExactly(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 30),
				LocalDate.of(2027, 1, 30), LocalDate.of(2027, 2, 28));
	}

	@Test
	void aSemiannualItemGeneratesOnlyThePeriodsOfItsPeriodicity() {
		Created created = service.create(USER_ID, values(Periodicity.SEMIANNUAL, 15, 0, "2026-11", null));

		assertThat(periodsOf(created)).containsExactly(ym("2026-11"), ym("2027-05"), ym("2027-11"), ym("2028-05"));
		assertThat(created.item().getGeneratedUntil()).isEqualTo(HORIZON);
	}

	@Test
	void withAnEndBeforeTheHorizonItGeneratesUpToTheEnd() {
		Created created = service.create(USER_ID, values(Periodicity.MONTHLY, 20, 0, "2026-10", "2026-12"));

		assertThat(periodsOf(created)).containsExactly(ym("2026-10"), ym("2026-11"), ym("2026-12"));
		assertThat(created.item().getGeneratedUntil()).isEqualTo(ym("2026-12"));
	}

	@Test
	void anEndAfterTheHorizonIsAcceptedAndItGeneratesUpToTheHorizon() {
		Created created = service.create(USER_ID, values(Periodicity.ANNUAL, 20, 0, "2026-10", "2035-10"));

		assertThat(periodsOf(created)).containsExactly(ym("2026-10"), ym("2027-10"), ym("2028-10"));
		assertThat(created.item().getEndPeriod()).isEqualTo(ym("2035-10"));
		assertThat(created.item().getGeneratedUntil()).isEqualTo(HORIZON);
	}

	@Test
	void anEndEqualToTheStartGeneratesASingleEntry() {
		Created created = service.create(USER_ID, values(Periodicity.MONTHLY, 20, 0, "2027-03", "2027-03"));

		assertThat(periodsOf(created)).containsExactly(ym("2027-03"));
		assertThat(created.item().getGeneratedUntil()).isEqualTo(ym("2027-03"));
	}

	@Test
	void theEntriesAreSavedInOneBatchWithASingleQueryForTheirPeriods() {
		service.create(USER_ID, monthly("2026-10"));

		verify(periods, times(1)).findByUserIdAndPeriodMonthIn(eq(USER_ID), anyCollection());
		verify(entries, times(1)).saveAll(anyList());
		verify(entries, never()).save(any());
	}

	@Test
	void theHorizonIsEnsuredBeforeGenerating() {
		service.create(USER_ID, monthly("2026-10"));

		InOrder order = inOrder(horizon, items, entries);
		order.verify(horizon).ensureHorizon(user);
		order.verify(items).save(any(BudgetItem.class));
		order.verify(entries).saveAll(anyList());
	}

	// --- Período de inicio (RN-06, RN-08, RN-10) ---

	@ParameterizedTest
	@ValueSource(strings = { "2026-08", "2026-09", "2026-10", "2027-06", "2028-10" })
	void aStartBetweenTheFirstOpenPeriodAndTheHorizonIsAccepted(String start) {
		Created created = service.create(USER_ID, monthly(start));

		assertThat(periodsOf(created).getFirst()).isEqualTo(ym(start));
		assertThat(periodsOf(created).getLast()).isEqualTo(HORIZON);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-07", "2025-12", "2028-11", "2030-01" })
	void aStartBeforeTheFirstOpenPeriodOrAfterTheHorizonIsNotAvailable(String start) {
		BusinessException e = rejected(() -> service.create(USER_ID, monthly(start)));

		assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_NOT_AVAILABLE);
		assertThat(e.getMessage()).contains("2026-08").contains("2028-10");
		verify(items, never()).save(any());
		verifyNoInteractions(entries, horizon);
	}

	@Test
	void withClosedPeriodsTheFirstOpenIsTheOneAfterTheLastClosed() {
		BudgetPeriod closed = BudgetPeriod.open(USER_ID, ym("2026-09"));
		closed.setStatus(PeriodStatus.CLOSED);
		when(periods.findFirstByUserIdAndStatusOrderByPeriodMonthDesc(USER_ID, PeriodStatus.CLOSED))
				.thenReturn(Optional.of(closed));

		assertThat(rejected(() -> service.create(USER_ID, monthly("2026-09"))).code())
				.isEqualTo(ErrorCode.PERIOD_NOT_AVAILABLE);
		assertThat(rejected(() -> service.create(USER_ID, monthly("2026-08"))).code())
				.isEqualTo(ErrorCode.PERIOD_NOT_AVAILABLE);
		assertThat(periodsOf(service.create(USER_ID, monthly("2026-10"))).getFirst()).isEqualTo(ym("2026-10"));
	}

	@Test
	void anEndBeforeTheStartIsAValidationErrorOfTheEndPeriod() {
		BusinessException e = rejected(
				() -> service.create(USER_ID, values(Periodicity.MONTHLY, 20, 0, "2026-12", "2026-11")));

		assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(e.field()).isEqualTo("endPeriod");
		verify(items, never()).save(any());
		verifyNoInteractions(entries);
	}

	@Test
	void anEndBeforeTheStartIsReportedBeforeAnUnavailableStart() {
		BusinessException e = rejected(
				() -> service.create(USER_ID, values(Periodicity.MONTHLY, 20, 0, "2030-01", "2029-12")));

		assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	// --- Aislamiento (RN-01) ---

	@Test
	void anAccountThatDoesNotExistOrBelongsToAnotherUserIsAValidationErrorOfItsField() {
		// El repositorio no la encuentra para este usuario, exista o no para otro.
		when(accounts.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());
		BudgetItemValues values = new BudgetItemValues("Luz", EntryKind.EXPENSE, 99L, null, Periodicity.MONTHLY, 10, 0,
				CURRENT, null, EstimationRule.LAST_VALUE, new BigDecimal("45000.00"));

		BusinessException e = rejected(() -> service.create(USER_ID, values));

		assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(e.field()).isEqualTo("defaultAccountId");
		assertThat(e.getMessage()).isEqualTo("La cuenta por defecto no existe.");
		verify(items, never()).save(any());
		verifyNoInteractions(entries);
	}

	@Test
	void aCategoryThatDoesNotExistOrBelongsToAnotherUserIsAValidationErrorOfItsField() {
		when(categories.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());
		BudgetItemValues values = new BudgetItemValues("Luz", EntryKind.EXPENSE, ACCOUNT_ID, 99L, Periodicity.MONTHLY,
				10, 0, CURRENT, null, EstimationRule.LAST_VALUE, new BigDecimal("45000.00"));

		BusinessException e = rejected(() -> service.create(USER_ID, values));

		assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(e.field()).isEqualTo("categoryId");
		assertThat(e.getMessage()).isEqualTo("La categoría no existe.");
		verify(items, never()).save(any());
		verifyNoInteractions(entries);
	}

	@Test
	void everyLookupUsesOnlyTheUserOfTheToken() {
		service.create(USER_ID, monthly("2026-10"));

		verify(accounts).findByIdAndUserId(ACCOUNT_ID, USER_ID);
		verify(categories).findByIdAndUserId(CATEGORY_ID, USER_ID);
		verify(periods).findFirstByUserIdAndStatusOrderByPeriodMonthDesc(USER_ID, PeriodStatus.CLOSED);
		verify(periods).findByUserIdAndPeriodMonthIn(eq(USER_ID), anyCollection());
		verify(accounts, never()).findById(anyLong());
		verify(categories, never()).findById(anyLong());
		verify(periods, never()).findAll();
	}

	@Test
	void aUserThatNoLongerExistsIsUnauthorized() {
		when(users.findById(8L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(8L, monthly("2026-10")))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
		verifyNoInteractions(accounts, categories, items, entries);
	}
}
