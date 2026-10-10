package com.smartcoin.entry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.DeletionScope;
import com.smartcoin.entry.domain.EntryDeletionPlanner.BlockReason;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Blocker;
import com.smartcoin.entry.domain.EntryDeletionPlanner.ItemOutcome;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.entry.service.EntryService.DeletionPreview;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-18 (RN-30, RN-31, RN-32, RN-09, RN-01): eliminar una partida y su vista previa, sin base de datos, con
 * repositorios simulados y el reloj en el 8 de octubre de 2026.
 *
 * <p>El Concepto «Luz» (id 31) empieza en 2026-10 y tiene cinco partidas, de 2026-10 a 2027-02, con ids 101 a 105. El
 * Concepto «Agua» (id 32) tiene una partida en cada mes, con ids 201 a 205, y sirve para comprobar que nada del
 * Concepto elegido toca el otro. Una partida puntual (id 900) está en 2026-11.
 */
class EntryDeletionServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final long ITEM_ID = 31;
	static final long OTHER_ITEM_ID = 32;
	static final long ONE_OFF_ID = 900;
	static final String[] MONTHS = { "2026-10", "2026-11", "2026-12", "2027-01", "2027-02" };

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	AccountRepository accounts = mock(AccountRepository.class);
	CategoryRepository categories = mock(CategoryRepository.class);
	BudgetItemRepository items = mock(BudgetItemRepository.class);
	EntryService service;

	BudgetItem light;
	BudgetItem water;
	final List<BudgetEntry> lightEntries = new ArrayList<>();
	final List<BudgetEntry> waterEntries = new ArrayList<>();
	final Set<Long> withMovements = new HashSet<>();

	@BeforeEach
	void setUp() {
		service = new EntryService(periods, entries, movements, accounts, categories, items,
				Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZONE));
		light = item(ITEM_ID, "Luz", null, "2028-10");
		water = item(OTHER_ITEM_ID, "Agua", null, "2028-10");
		for (int i = 0; i < MONTHS.length; i++) {
			lightEntries.add(recurring(101 + i, light, MONTHS[i], StoredEntryStatus.PENDING));
			waterEntries.add(recurring(201 + i, water, MONTHS[i], StoredEntryStatus.PENDING));
		}
		stubLookups(lightEntries, waterEntries);
		when(entries.deleteByUserIdAndIdIn(eq(USER_ID), anyCollection()))
				.thenAnswer(call -> call.<Collection<Long>>getArgument(1).size());
		// La consulta de movimientos devuelve, de las partidas pedidas, las que tienen alguno.
		when(movements.findEntryIdsWithMovements(eq(USER_ID), anyCollection())).thenAnswer(call -> call
				.<Collection<Long>>getArgument(1).stream().filter(withMovements::contains).toList());
	}

	void stubLookups(List<BudgetEntry> light, List<BudgetEntry> water) {
		for (BudgetEntry entry : light) {
			when(entries.findByIdAndUserIdWithDetails(entry.getId(), USER_ID)).thenReturn(Optional.of(entry));
		}
		for (BudgetEntry entry : water) {
			when(entries.findByIdAndUserIdWithDetails(entry.getId(), USER_ID)).thenReturn(Optional.of(entry));
		}
		when(entries.findByUserIdAndBudgetItemId(USER_ID, ITEM_ID)).thenReturn(light);
		when(entries.findByUserIdAndBudgetItemId(USER_ID, OTHER_ITEM_ID)).thenReturn(water);
	}

	static BudgetItem item(long id, String name, String end, String generatedUntil) {
		BudgetItem item = new BudgetItem();
		ReflectionTestUtils.setField(item, "id", id);
		item.setUserId(USER_ID);
		item.setName(name);
		item.setStartPeriod(YearMonth.of(2026, 10));
		item.setEndPeriod(end == null ? null : YearMonth.parse(end));
		item.setGeneratedUntil(generatedUntil == null ? null : YearMonth.parse(generatedUntil));
		return item;
	}

	static BudgetPeriod period(String month, PeriodStatus status) {
		BudgetPeriod period = BudgetPeriod.open(USER_ID, YearMonth.parse(month));
		period.setStatus(status);
		return period;
	}

	static BudgetEntry base(long id, String month, StoredEntryStatus status) {
		BudgetEntry entry = new BudgetEntry();
		ReflectionTestUtils.setField(entry, "id", id);
		entry.setUserId(USER_ID);
		entry.setPeriod(period(month, PeriodStatus.OPEN));
		entry.setKind(EntryKind.EXPENSE);
		entry.setStatus(status);
		return entry;
	}

	static BudgetEntry recurring(long id, BudgetItem item, String month, StoredEntryStatus status) {
		BudgetEntry entry = base(id, month, status);
		entry.setOrigin(EntryOrigin.RECURRING);
		entry.setBudgetItem(item);
		return entry;
	}

	BudgetEntry oneOff(EntryOrigin origin, StoredEntryStatus status) {
		BudgetEntry entry = base(ONE_OFF_ID, "2026-11", status);
		entry.setOrigin(origin);
		entry.setName("Service del auto");
		when(entries.findByIdAndUserIdWithDetails(ONE_OFF_ID, USER_ID)).thenReturn(Optional.of(entry));
		return entry;
	}

	BudgetEntry light(String month) {
		return lightEntries.stream().filter(e -> e.getPeriod().getPeriodMonth().equals(YearMonth.parse(month)))
				.findFirst().orElseThrow();
	}

	@SuppressWarnings("unchecked")
	Collection<Long> deletedIds() {
		ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(entries).deleteByUserIdAndIdIn(eq(USER_ID), captor.capture());
		return captor.getValue();
	}

	void assertNothingWasChanged() {
		verify(entries, never()).deleteByUserIdAndIdIn(anyLong(), anyCollection());
		verify(entries, never()).save(any());
		verify(entries, never()).saveAll(anyCollection());
		verify(items, never()).deleteByUserIdAndId(anyLong(), anyLong());
		verify(items, never()).save(any());
		assertThat(light.getEndPeriod()).isNull();
		assertThat(light.getGeneratedUntil()).isEqualTo(YearMonth.of(2028, 10));
	}

	static BusinessException rejection(Runnable action) {
		try {
			action.run();
		}
		catch (BusinessException e) {
			return e;
		}
		throw new AssertionError("Se esperaba una BusinessException");
	}

	// ---------------------------------------------------------------- Solo este mes

	@Nested
	class OnlyThisMonth {

		@Test
		void deletesOnlyThatEntryAndLeavesTheItemAndItsGeneratedUntilAsTheyWere() {
			service.delete(USER_ID, 103, DeletionScope.ONLY_THIS);

			assertThat(deletedIds()).containsExactly(103L);
			verify(items, never()).deleteByUserIdAndId(anyLong(), anyLong());
			assertThat(light.getEndPeriod()).isNull();
			// generated_until no cambia: así la partida no reaparece (RN-13).
			assertThat(light.getGeneratedUntil()).isEqualTo(YearMonth.of(2028, 10));
		}

		@Test
		void otherEntriesOfTheItemDoNotBlockIt() {
			light("2026-10").setStatus(StoredEntryStatus.CONSOLIDATED);
			light("2027-01").setStatus(StoredEntryStatus.CONSOLIDATED);
			withMovements.add(104L);
			withMovements.add(102L);

			service.delete(USER_ID, 103, DeletionScope.ONLY_THIS);

			assertThat(deletedIds()).containsExactly(103L);
		}

		@Test
		void aConsolidatedEntryIsRejectedWithItsId() {
			light("2026-12").setStatus(StoredEntryStatus.CONSOLIDATED);

			BusinessException e = rejection(() -> service.delete(USER_ID, 103, DeletionScope.ONLY_THIS));

			assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING);
			assertThat(e.entries()).containsExactly(103L);
			assertNothingWasChanged();
		}

		@Test
		void anEntryWithMovementsIsRejectedWithItsId() {
			withMovements.add(103L);

			BusinessException e = rejection(() -> service.delete(USER_ID, 103, DeletionScope.ONLY_THIS));

			assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_HAS_MOVEMENTS);
			assertThat(e.entries()).containsExactly(103L);
			assertThat(e.getMessage()).contains("movimientos");
			assertNothingWasChanged();
		}

		@Test
		void theLastEntryOfAnItemWithNothingLeftToGenerateRemovesTheItemAfterItsEntries() {
			BudgetItem plan = item(ITEM_ID, "Heladera", "2026-10", "2026-10");
			BudgetEntry only = recurring(110, plan, "2026-10", StoredEntryStatus.PENDING);
			stubLookups(List.of(only), List.of());

			service.delete(USER_ID, 110, DeletionScope.ONLY_THIS);

			var order = inOrder(entries, items);
			order.verify(entries).deleteByUserIdAndIdIn(eq(USER_ID), anyCollection());
			order.verify(items).deleteByUserIdAndId(USER_ID, ITEM_ID);
		}

		@Test
		void theLastEntryOfAnItemThatStillHasToGenerateKeepsTheItem() {
			// Sin fin: el horizonte le va a generar las de los meses que entren, así que «solo este mes» es solo eso.
			BudgetItem open = item(ITEM_ID, "Luz", null, "2026-10");
			BudgetEntry only = recurring(110, open, "2026-10", StoredEntryStatus.PENDING);
			stubLookups(List.of(only), List.of());

			service.delete(USER_ID, 110, DeletionScope.ONLY_THIS);

			assertThat(deletedIds()).containsExactly(110L);
			verify(items, never()).deleteByUserIdAndId(anyLong(), anyLong());
			assertThat(open.getEndPeriod()).isNull();
			assertThat(open.getGeneratedUntil()).isEqualTo(YearMonth.of(2026, 10));
		}
	}

	// ---------------------------------------------------------------- Este mes y los siguientes

	@Nested
	class ThisAndFuture {

		@Test
		void deletesFromThatPeriodOnAndEndsTheItemTheMonthBefore() {
			service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE);

			assertThat(deletedIds()).containsExactlyInAnyOrder(103L, 104L, 105L);
			assertThat(light.getEndPeriod()).isEqualTo(YearMonth.of(2026, 11));
			verify(items, never()).deleteByUserIdAndId(anyLong(), anyLong());
			// generated_until tampoco cambia acá: siempre queda en o después del fin nuevo, así no genera más.
			assertThat(light.getGeneratedUntil()).isEqualTo(YearMonth.of(2028, 10));
		}

		@Test
		void endsInDecemberWhenTheYearChanges() {
			service.delete(USER_ID, 104, DeletionScope.THIS_AND_FUTURE);

			assertThat(deletedIds()).containsExactlyInAnyOrder(104L, 105L);
			assertThat(light.getEndPeriod()).isEqualTo(YearMonth.of(2026, 12));
		}

		@Test
		void fromTheFirstPeriodTheItemDisappearsAfterAllItsEntries() {
			service.delete(USER_ID, 101, DeletionScope.THIS_AND_FUTURE);

			var order = inOrder(entries, items);
			order.verify(entries).deleteByUserIdAndIdIn(eq(USER_ID), anyCollection());
			order.verify(items).deleteByUserIdAndId(USER_ID, ITEM_ID);
			assertThat(deletedIds()).containsExactlyInAnyOrder(101L, 102L, 103L, 104L, 105L);
		}

		@Test
		void ifTheEarlierEntriesWereAlreadyDeletedTheItemDisappearsEvenIfThePeriodIsNotItsStart() {
			lightEntries.removeIf(e -> e.getId() == 101 || e.getId() == 102);

			service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE);

			verify(items).deleteByUserIdAndId(USER_ID, ITEM_ID);
		}

		@Test
		void aPlanIsCutAndKeepsItsTotal() {
			BudgetItem plan = item(ITEM_ID, "Heladera", "2027-02", "2027-02");
			plan.setInstallmentsTotal(12);
			plan.setFirstInstallmentNumber(4);
			lightEntries.clear();
			for (int i = 0; i < MONTHS.length; i++) {
				lightEntries.add(recurring(101 + i, plan, MONTHS[i], StoredEntryStatus.PENDING));
			}
			stubLookups(lightEntries, waterEntries);

			service.delete(USER_ID, 104, DeletionScope.THIS_AND_FUTURE);

			assertThat(plan.getEndPeriod()).isEqualTo(YearMonth.of(2026, 12));
			assertThat(plan.getInstallmentsTotal()).isEqualTo(12);
			assertThat(plan.getFirstInstallmentNumber()).isEqualTo(4);
		}

		@Test
		void aConsolidatedEntryInTheFutureRejectsEverythingAndChangesNothing() {
			light("2027-01").setStatus(StoredEntryStatus.CONSOLIDATED);

			BusinessException e = rejection(() -> service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE));

			assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING);
			assertThat(e.entries()).containsExactly(104L);
			assertNothingWasChanged();
		}

		@Test
		void anEntryWithMovementsInTheFutureRejectsEverythingAndChangesNothing() {
			withMovements.add(105L);

			BusinessException e = rejection(() -> service.delete(USER_ID, 102, DeletionScope.THIS_AND_FUTURE));

			assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_HAS_MOVEMENTS);
			assertThat(e.entries()).containsExactly(105L);
			assertNothingWasChanged();
		}

		@Test
		void consolidatedAndWithMovementsAtTheSameTimeAnswersNotPendingAndListsAllOfThem() {
			light("2026-12").setStatus(StoredEntryStatus.CONSOLIDATED);
			withMovements.add(104L);
			withMovements.add(105L);

			BusinessException e = rejection(() -> service.delete(USER_ID, 102, DeletionScope.THIS_AND_FUTURE));

			assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING);
			assertThat(e.entries()).containsExactly(103L, 104L, 105L);
			assertThat(e.getMessage()).contains("1 partida está consolidada").contains("2 partidas tienen movimientos");
			assertNothingWasChanged();
		}

		@Test
		void earlierEntriesNeverBlockEvenIfConsolidatedOrWithMovements() {
			light("2026-10").setStatus(StoredEntryStatus.CONSOLIDATED);
			withMovements.add(102L);

			service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE);

			assertThat(deletedIds()).containsExactlyInAnyOrder(103L, 104L, 105L);
			assertThat(light.getEndPeriod()).isEqualTo(YearMonth.of(2026, 11));
		}

		@Test
		void asksForMovementsOnceAndOnlyForThePendingEntriesOfTheScope() {
			light("2027-01").setStatus(StoredEntryStatus.CONSOLIDATED);

			rejection(() -> service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE));

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Collection<Long>> asked = ArgumentCaptor.forClass(Collection.class);
			verify(movements, times(1)).findEntryIdsWithMovements(eq(USER_ID), asked.capture());
			// 103 y 105: pendientes y con período igual o posterior. 101 y 102 son anteriores; 104 está consolidada.
			assertThat(asked.getValue()).containsExactlyInAnyOrder(103L, 105L);
		}
	}

	// ---------------------------------------------------------------- Partida sin Concepto

	@Nested
	class WithoutItem {

		@Test
		void deletesAPendingEntryWithoutMovements() {
			oneOff(EntryOrigin.ONE_OFF, StoredEntryStatus.PENDING);

			service.delete(USER_ID, ONE_OFF_ID, null);

			assertThat(deletedIds()).containsExactly(ONE_OFF_ID);
			// No consulta ningún Concepto ni toca ninguno.
			verify(entries, never()).findByUserIdAndBudgetItemId(anyLong(), anyLong());
			verifyNoInteractions(items);
		}

		@Test
		void anEntryWithMovementsIsRejected() {
			oneOff(EntryOrigin.ONE_OFF, StoredEntryStatus.PENDING);
			withMovements.add(ONE_OFF_ID);

			BusinessException e = rejection(() -> service.delete(USER_ID, ONE_OFF_ID, null));

			assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_HAS_MOVEMENTS);
			assertThat(e.entries()).containsExactly(ONE_OFF_ID);
			verify(entries, never()).deleteByUserIdAndIdIn(anyLong(), anyCollection());
		}

		@Test
		void aConsolidatedEntryIsRejected() {
			oneOff(EntryOrigin.ONE_OFF, StoredEntryStatus.CONSOLIDATED);

			BusinessException e = rejection(() -> service.delete(USER_ID, ONE_OFF_ID, null));

			assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING);
			assertThat(e.entries()).containsExactly(ONE_OFF_ID);
			verify(entries, never()).deleteByUserIdAndIdIn(anyLong(), anyCollection());
		}

		@Test
		void carriedOverAndClosingDifferenceEntriesAreDeletedLikeAnyEntryWithoutItem() {
			for (EntryOrigin origin : List.of(EntryOrigin.CARRIED_OVER, EntryOrigin.CLOSING_DIFFERENCE)) {
				oneOff(origin, StoredEntryStatus.PENDING);

				service.delete(USER_ID, ONE_OFF_ID, null);
			}

			verify(entries, times(2)).deleteByUserIdAndIdIn(eq(USER_ID), anyCollection());
		}
	}

	// ---------------------------------------------------------------- Errores y orden

	@Nested
	class Errors {

		@Test
		void aRecurringEntryWithoutAScopeIsAValidationErrorOnScope() {
			BusinessException e = rejection(() -> service.delete(USER_ID, 103, null));

			assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(e.field()).isEqualTo("scope");
			assertNothingWasChanged();
		}

		@Test
		void anEntryWithoutAnItemWithAScopeIsAValidationErrorOnScope() {
			oneOff(EntryOrigin.ONE_OFF, StoredEntryStatus.PENDING);

			for (DeletionScope scope : DeletionScope.values()) {
				BusinessException e = rejection(() -> service.delete(USER_ID, ONE_OFF_ID, scope));

				assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
				assertThat(e.field()).isEqualTo("scope");
			}
			verify(entries, never()).deleteByUserIdAndIdIn(anyLong(), anyCollection());
		}

		@Test
		void aClosedPeriodIsPeriodClosedForTheOneOffAndForBothScopes() {
			oneOff(EntryOrigin.ONE_OFF, StoredEntryStatus.PENDING).getPeriod().setStatus(PeriodStatus.CLOSED);
			light("2026-12").getPeriod().setStatus(PeriodStatus.CLOSED);

			assertThat(rejection(() -> service.delete(USER_ID, ONE_OFF_ID, null)).code())
					.isEqualTo(ErrorCode.PERIOD_CLOSED);
			assertThat(rejection(() -> service.delete(USER_ID, 103, DeletionScope.ONLY_THIS)).code())
					.isEqualTo(ErrorCode.PERIOD_CLOSED);
			assertThat(rejection(() -> service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE)).code())
					.isEqualTo(ErrorCode.PERIOD_CLOSED);
			assertNothingWasChanged();
			// Antes que mirar partidas ni movimientos.
			verify(entries, never()).findByUserIdAndBudgetItemId(anyLong(), anyLong());
			verifyNoInteractions(movements);
		}

		@Test
		void aClosedPeriodIsReportedBeforeConsolidated() {
			// En un período cerrado todas están consolidadas: el motivo útil es el mes (como al editar, D-30).
			BudgetEntry closed = light("2026-12");
			closed.setStatus(StoredEntryStatus.CONSOLIDATED);
			closed.getPeriod().setStatus(PeriodStatus.CLOSED);

			assertThat(rejection(() -> service.delete(USER_ID, 103, DeletionScope.ONLY_THIS)).code())
					.isEqualTo(ErrorCode.PERIOD_CLOSED);
		}

		@Test
		void anIdOfAnotherUserIsNotFoundForTheOneOffAndForBothScopes() {
			// El repositorio no encuentra nada para el usuario 8.
			when(entries.findByIdAndUserIdWithDetails(anyLong(), eq(OTHER_USER_ID))).thenReturn(Optional.empty());

			for (DeletionScope scope : new DeletionScope[] { null, DeletionScope.ONLY_THIS,
					DeletionScope.THIS_AND_FUTURE }) {
				BusinessException e = rejection(() -> service.delete(OTHER_USER_ID, 103, scope));

				assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
			}
			verify(entries, times(3)).findByIdAndUserIdWithDetails(103L, OTHER_USER_ID);
			verify(entries, never()).deleteByUserIdAndIdIn(anyLong(), anyCollection());
			verifyNoInteractions(items, movements);
		}

		@Test
		void aMissingEntryIsNotFoundBeforeTheScopeIsChecked() {
			when(entries.findByIdAndUserIdWithDetails(999L, USER_ID)).thenReturn(Optional.empty());

			BusinessException e = rejection(() -> service.delete(USER_ID, 999, null));

			assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
		}

		@Test
		void ifTheDeleteAffectsFewerRowsThanExpectedItFailsSoTheTransactionRollsBack() {
			when(entries.deleteByUserIdAndIdIn(eq(USER_ID), anyCollection())).thenReturn(1);

			assertThatThrownBy(() -> service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE))
					.isInstanceOf(IllegalStateException.class);
			verify(items, never()).deleteByUserIdAndId(anyLong(), anyLong());
		}
	}

	// ---------------------------------------------------------------- Aislamiento

	@Nested
	class Isolation {

		@Test
		void itNeverTouchesEntriesOfOtherItemsEvenInTheSamePeriods() {
			service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE);

			assertThat(deletedIds()).doesNotContainAnyElementsOf(List.of(201L, 202L, 203L, 204L, 205L));
			verify(entries, never()).findByUserIdAndBudgetItemId(USER_ID, OTHER_ITEM_ID);
			assertThat(water.getEndPeriod()).isNull();
			verify(items, never()).deleteByUserIdAndId(USER_ID, OTHER_ITEM_ID);
		}

		@Test
		void everyQueryAndEveryDeleteCarriesTheCurrentUser() {
			service.delete(USER_ID, 103, DeletionScope.THIS_AND_FUTURE);

			verify(entries).findByIdAndUserIdWithDetails(103L, USER_ID);
			verify(entries).findByUserIdAndBudgetItemId(USER_ID, ITEM_ID);
			verify(movements).findEntryIdsWithMovements(eq(USER_ID), anyCollection());
			verify(entries).deleteByUserIdAndIdIn(eq(USER_ID), anyCollection());
			verify(entries, never()).findByUserIdAndBudgetItemId(eq(OTHER_USER_ID), anyLong());
			verify(entries, never()).deleteByUserIdAndIdIn(eq(OTHER_USER_ID), anyCollection());
		}

		@Test
		void removingAnItemDeletesItByUserAndId() {
			service.delete(USER_ID, 101, DeletionScope.THIS_AND_FUTURE);

			verify(items).deleteByUserIdAndId(USER_ID, ITEM_ID);
			verify(items, never()).deleteByUserIdAndId(eq(OTHER_USER_ID), anyLong());
		}
	}

	// ---------------------------------------------------------------- Vista previa

	@Nested
	class Preview {

		@Test
		void aRecurringEntryShowsBothScopesWithoutChangingAnything() {
			DeletionPreview preview = service.deletionPreview(USER_ID, 103);

			assertThat(preview.recurring()).isTrue();
			assertThat(preview.removal()).isNull();
			assertThat(preview.period()).isEqualTo(YearMonth.of(2026, 12));
			assertThat(preview.onlyThis().toDeleteIds()).containsExactly(103L);
			assertThat(preview.onlyThis().itemOutcome()).isEqualTo(ItemOutcome.KEEPS_ITEM);
			assertThat(preview.thisAndFuture().toDeleteIds()).containsExactly(103L, 104L, 105L);
			assertThat(preview.thisAndFuture().itemOutcome()).isEqualTo(ItemOutcome.ENDS_ITEM);
			assertThat(preview.thisAndFuture().newEndPeriod()).isEqualTo(YearMonth.of(2026, 11));
			assertNothingWasChanged();
			assertThat(light.getEndPeriod()).isNull();
		}

		@Test
		void fromTheFirstPeriodTheItemWouldDisappear() {
			DeletionPreview preview = service.deletionPreview(USER_ID, 101);

			assertThat(preview.thisAndFuture().itemOutcome()).isEqualTo(ItemOutcome.REMOVES_ITEM);
			assertThat(preview.thisAndFuture().toDeleteIds()).hasSize(5);
		}

		@Test
		void listsTheBlockersOfEachScopeByPeriodAndReason() {
			light("2027-01").setStatus(StoredEntryStatus.CONSOLIDATED);
			withMovements.add(105L);

			DeletionPreview preview = service.deletionPreview(USER_ID, 103);

			assertThat(preview.onlyThis().allowed()).isTrue();
			assertThat(preview.thisAndFuture().allowed()).isFalse();
			assertThat(preview.thisAndFuture().blockers()).containsExactly(
					new Blocker(104L, YearMonth.of(2027, 1), BlockReason.CONSOLIDATED),
					new Blocker(105L, YearMonth.of(2027, 2), BlockReason.HAS_MOVEMENTS));
			assertThat(preview.thisAndFuture().primaryReason()).isEqualTo(BlockReason.CONSOLIDATED);
		}

		@Test
		void anEntryWithoutAnItemShowsASinglePlan() {
			oneOff(EntryOrigin.CARRIED_OVER, StoredEntryStatus.PENDING);
			withMovements.add(ONE_OFF_ID);

			DeletionPreview preview = service.deletionPreview(USER_ID, ONE_OFF_ID);

			assertThat(preview.recurring()).isFalse();
			assertThat(preview.origin()).isEqualTo(EntryOrigin.CARRIED_OVER);
			assertThat(preview.onlyThis()).isNull();
			assertThat(preview.thisAndFuture()).isNull();
			assertThat(preview.removal().allowed()).isFalse();
			assertThat(preview.removal().primaryReason()).isEqualTo(BlockReason.HAS_MOVEMENTS);
		}

		@Test
		void aClosedPeriodAndAnotherUsersEntryAreRejectedLikeTheDeletion() {
			light("2026-12").getPeriod().setStatus(PeriodStatus.CLOSED);
			when(entries.findByIdAndUserIdWithDetails(103L, OTHER_USER_ID)).thenReturn(Optional.empty());

			assertThat(rejection(() -> service.deletionPreview(USER_ID, 103)).code())
					.isEqualTo(ErrorCode.PERIOD_CLOSED);
			assertThat(rejection(() -> service.deletionPreview(OTHER_USER_ID, 103)).code())
					.isEqualTo(ErrorCode.NOT_FOUND);
		}

		@Test
		void itAnswersWithTheSamePlanTheDeletionWouldApply() {
			light("2027-02").setStatus(StoredEntryStatus.CONSOLIDATED);

			DeletionPreview preview = service.deletionPreview(USER_ID, 102);
			BusinessException e = rejection(() -> service.delete(USER_ID, 102, DeletionScope.THIS_AND_FUTURE));

			assertThat(preview.thisAndFuture().blockerIds()).isEqualTo(e.entries());
			assertThat(preview.thisAndFuture().allowed()).isFalse();
		}
	}
}
