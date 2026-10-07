package com.smartcoin.budgetitem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.BudgetItemStatus;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.InstallmentPlan;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.budgetitem.service.BudgetItemService.ListRow;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** HU-14, D-27, D-28 y RN-01: sin base de datos, con repositorios simulados y el reloj en octubre de 2026. */
class BudgetItemListServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final long TAXES_ID = 5;
	static final long HOME_ID = 6;
	static final long FOREIGN_CATEGORY_ID = 99;

	BudgetItemRepository items = mock(BudgetItemRepository.class);
	CategoryRepository categories = mock(CategoryRepository.class);
	BudgetItemService service;
	Category taxes;
	Category home;
	Account pesos;
	long nextId = 1;

	@BeforeEach
	void setUp() {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, 10),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		Clock clock = Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"), ZONE);
		service = new BudgetItemService(items, mock(BudgetEntryRepository.class), mock(MovementRepository.class),
				mock(AccountRepository.class), categories, mock(BudgetPeriodRepository.class),
				mock(UserRepository.class), mock(HorizonService.class), mock(EntryGenerator.class), properties, clock);

		taxes = category(TAXES_ID, "Impuestos");
		home = category(HOME_ID, "Hogar");
		pesos = new Account();
		ReflectionTestUtils.setField(pesos, "id", 42L);
		pesos.setName("Banco");
		pesos.setCurrency(Currency.ARS);
		when(categories.findByIdAndUserId(TAXES_ID, USER_ID)).thenReturn(Optional.of(taxes));
		when(categories.findByIdAndUserId(HOME_ID, USER_ID)).thenReturn(Optional.of(home));
		when(categories.findByIdAndUserId(FOREIGN_CATEGORY_ID, USER_ID)).thenReturn(Optional.empty());
	}

	static Category category(long id, String name) {
		Category c = new Category();
		ReflectionTestUtils.setField(c, "id", id);
		c.setName(name);
		return c;
	}

	BudgetItem item(String name, EntryKind kind, Category category, String start, String end) {
		BudgetItem i = new BudgetItem();
		ReflectionTestUtils.setField(i, "id", nextId++);
		i.setUserId(USER_ID);
		i.setName(name);
		i.setKind(kind);
		i.setCategory(category);
		i.setDefaultAccount(pesos);
		i.setPeriodicity(Periodicity.MONTHLY);
		i.setDueDay(10);
		i.setDueMonthOffset(0);
		i.setStartPeriod(YearMonth.parse(start));
		i.setEndPeriod(end == null ? null : YearMonth.parse(end));
		i.setEstimationRule(EstimationRule.LAST_VALUE);
		i.setCurrentAmount(new BigDecimal("100.00"));
		return i;
	}

	/** Cuatro Conceptos: un ingreso sin categoría, dos gastos con categoría y uno finalizado. */
	void givenSomeItems() {
		when(items.findAllByUserIdWithAccountAndCategory(USER_ID)).thenReturn(List.of(
				item("sueldo", EntryKind.INCOME, null, "2026-01", null),
				item("Monotributo", EntryKind.EXPENSE, taxes, "2026-01", null),
				item("Alquiler", EntryKind.EXPENSE, home, "2026-01", null),
				item("Antiguo", EntryKind.EXPENSE, taxes, "2025-01", "2026-06")));
	}

	static List<String> names(List<ListRow> rows) {
		return rows.stream().map(r -> r.item().getName()).toList();
	}

	@Test
	void withoutFiltersTheFinishedGoLastAndTheRestAreOrderedByNameIgnoringCase() {
		givenSomeItems();

		List<ListRow> rows = service.list(USER_ID, null, null, false);

		assertThat(names(rows)).containsExactly("Alquiler", "Monotributo", "sueldo", "Antiguo");
		assertThat(rows.getLast().state().status()).isEqualTo(BudgetItemStatus.FINISHED);
	}

	@Test
	void filtersByKind() {
		givenSomeItems();

		assertThat(names(service.list(USER_ID, EntryKind.INCOME, null, false))).containsExactly("sueldo");
		assertThat(names(service.list(USER_ID, EntryKind.EXPENSE, null, false)))
				.containsExactly("Alquiler", "Monotributo", "Antiguo");
	}

	@Test
	void filtersByCategory() {
		givenSomeItems();

		assertThat(names(service.list(USER_ID, null, TAXES_ID, false))).containsExactly("Monotributo", "Antiguo");
	}

	@Test
	void filtersByNoCategory() {
		givenSomeItems();

		assertThat(names(service.list(USER_ID, null, null, true))).containsExactly("sueldo");
	}

	@Test
	void combinesKindAndCategory() {
		givenSomeItems();

		assertThat(names(service.list(USER_ID, EntryKind.INCOME, TAXES_ID, false))).isEmpty();
		assertThat(names(service.list(USER_ID, EntryKind.EXPENSE, HOME_ID, false))).containsExactly("Alquiler");
		assertThat(names(service.list(USER_ID, EntryKind.EXPENSE, null, true))).isEmpty();
	}

	@Test
	void aCategoryFromAnotherUserOrNonexistentIsNotFoundAndNothingIsListed() {
		assertThatThrownBy(() -> service.list(USER_ID, null, FOREIGN_CATEGORY_ID, false))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
		verify(items, never()).findAllByUserIdWithAccountAndCategory(USER_ID);
	}

	@Test
	void aCategoryAndNoCategoryTogetherAreInvalid() {
		assertThatThrownBy(() -> service.list(USER_ID, null, TAXES_ID, true))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("withoutCategory");
				});
		verifyNoInteractions(items);
	}

	@Test
	void onlyTheCurrentUsersItemsAreRequested() {
		BudgetItem foreign = item("Ajeno", EntryKind.EXPENSE, null, "2026-01", null);
		foreign.setUserId(OTHER_USER_ID);
		when(items.findAllByUserIdWithAccountAndCategory(OTHER_USER_ID)).thenReturn(List.of(foreign));
		when(items.findAllByUserIdWithAccountAndCategory(USER_ID)).thenReturn(List.of());

		assertThat(service.list(USER_ID, null, null, false)).isEmpty();
		verify(items).findAllByUserIdWithAccountAndCategory(USER_ID);
		verify(items, never()).findAllByUserIdWithAccountAndCategory(OTHER_USER_ID);
	}

	@Test
	void eachRowCarriesAccountCurrencyCategoryAndInstallmentState() {
		BudgetItem fridge = item("Heladera", EntryKind.EXPENSE, home, "2026-10", "2027-06");
		fridge.setInstallmentsTotal(12);
		fridge.setFirstInstallmentNumber(4);
		assertThat(fridge.installmentPlan()).isEqualTo(new InstallmentPlan(12, 4));
		when(items.findAllByUserIdWithAccountAndCategory(USER_ID)).thenReturn(List.of(fridge));

		ListRow row = service.list(USER_ID, null, null, false).getFirst();

		assertThat(row.accountName()).isEqualTo("Banco");
		assertThat(row.currency()).isEqualTo(Currency.ARS);
		assertThat(row.categoryName()).isEqualTo("Hogar");
		assertThat(row.state().status()).isEqualTo(BudgetItemStatus.ACTIVE);
		assertThat(row.state().currentInstallment()).isEqualTo(4);
		assertThat(row.state().installmentsRemaining()).isEqualTo(8);
	}
}
