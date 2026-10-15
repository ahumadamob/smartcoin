package com.smartcoin.entry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.ManualEntriesPolicy;
import com.smartcoin.entry.domain.QuickSettleBlockReason;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.entry.service.QuickSettleService.Preview;
import com.smartcoin.entry.service.QuickSettleService.Request;
import com.smartcoin.entry.service.QuickSettleService.Result;
import com.smartcoin.movement.domain.Movement;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.movement.service.MovementService;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-22 (RN-24, RN-21, RN-25, RN-26, RN-01): sin base de datos, con repositorios simulados y el reloj fijo, y con los
 * servicios de movimientos y de consolidación de verdad: el pago rápido los reutiliza. Un Concepto «Sueldo» de último
 * valor, con partidas de 2026-10 a 2027-01 presupuestadas en 1.800.000,00; hoy es el 15 de octubre de 2026 y la
 * ventana de anticipación es de 10 días. El repositorio de movimientos es en memoria: suma de verdad lo que se
 * guarda.
 */
class QuickSettleServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");
	static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;

	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	BudgetItemRepository items = mock(BudgetItemRepository.class);
	AccountRepository accounts = mock(AccountRepository.class);
	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	List<Movement> stored = new ArrayList<>();
	QuickSettleService service;

	Account bank = account(42, "Banco", Currency.ARS, "2026-08-01");
	Account wallet = account(44, "Billetera", Currency.ARS, "2026-08-01");
	Account dollars = account(43, "Caja en dólares", Currency.USD, "2026-08-01");
	Account lateAccount = account(45, "Cuenta nueva", Currency.ARS, "2026-10-10");

	BudgetItem salary = item();
	List<BudgetEntry> concept = new ArrayList<>();
	BudgetEntry october;
	BudgetEntry november;
	BudgetEntry december;
	BudgetEntry january;
	long nextId = 500;

	@BeforeEach
	void setUp() {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, 10),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		Clock clock = Clock.fixed(NOW, ZONE);
		MovementService movementService = new MovementService(entries, movements, accounts, periods, properties,
				clock);
		ConsolidationService consolidation = new ConsolidationService(entries, movements, items, clock);
		service = new QuickSettleService(entries, movements, items, movementService, consolidation, clock);

		when(accounts.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(bank));
		when(accounts.findByIdAndUserId(44L, USER_ID)).thenReturn(Optional.of(wallet));
		when(accounts.findByIdAndUserId(43L, USER_ID)).thenReturn(Optional.of(dollars));
		when(accounts.findByIdAndUserId(45L, USER_ID)).thenReturn(Optional.of(lateAccount));
		when(movements.save(any(Movement.class))).thenAnswer(call -> {
			Movement m = call.getArgument(0);
			ReflectionTestUtils.setField(m, "id", 5000L + stored.size());
			stored.add(m);
			return m;
		});
		when(movements.sumByEntry(anyLong(), anyLong())).thenAnswer(call -> stored.stream()
				.filter(m -> m.getEntry().getId().equals(call.getArgument(1)))
				.map(Movement::getAmount).reduce(BigDecimal::add).orElse(null));

		october = entry("2026-10", "1800000.00");
		november = entry("2026-11", "1800000.00");
		december = entry("2026-12", "1800000.00");
		january = entry("2027-01", "1800000.00");
		when(entries.findByUserIdAndBudgetItemId(USER_ID, salary.getId())).thenReturn(concept);
	}

	static Account account(long id, String name, Currency currency, String openingDate) {
		Account a = new Account();
		ReflectionTestUtils.setField(a, "id", id);
		a.setName(name);
		a.setCurrency(currency);
		a.setOpeningDate(LocalDate.parse(openingDate));
		return a;
	}

	static BudgetItem item() {
		BudgetItem item = new BudgetItem();
		ReflectionTestUtils.setField(item, "id", 31L);
		item.setName("Sueldo");
		item.setEstimationRule(EstimationRule.LAST_VALUE);
		item.setCurrentAmount(new BigDecimal("1800000.00"));
		return item;
	}

	BudgetEntry entry(String month, String budgeted) {
		BudgetPeriod period = BudgetPeriod.open(USER_ID, YearMonth.parse(month));
		period.setStatus(PeriodStatus.OPEN);
		when(periods.findByUserIdAndPeriodMonth(USER_ID, period.getPeriodMonth())).thenReturn(Optional.of(period));
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", nextId++);
		e.setUserId(USER_ID);
		e.setBudgetItem(salary);
		e.setOrigin(EntryOrigin.RECURRING);
		e.setKind(EntryKind.INCOME);
		e.setAccount(bank);
		e.setBudgetedAmount(new BigDecimal(budgeted));
		e.setStatus(StoredEntryStatus.PENDING);
		e.setPeriod(period);
		e.setDueDate(period.getPeriodMonth().atDay(5));
		concept.add(e);
		when(entries.findByIdAndUserIdWithDetails(e.getId(), USER_ID)).thenReturn(Optional.of(e));
		return e;
	}

	/** Un movimiento ya registrado en la partida, como si lo hubiera cargado el usuario. */
	void paid(BudgetEntry entry, String amount) {
		Movement m = new Movement();
		ReflectionTestUtils.setField(m, "id", 4000L + stored.size());
		m.setUserId(USER_ID);
		m.setEntry(entry);
		m.setAccount(bank);
		m.setMovementDate(TODAY);
		m.setAmount(new BigDecimal(amount));
		stored.add(m);
	}

	void consolidated(BudgetEntry entry, String amount) {
		entry.setStatus(StoredEntryStatus.CONSOLIDATED);
		entry.setConsolidatedAmount(new BigDecimal(amount));
	}

	Result settle(BudgetEntry entry) {
		return service.quickSettle(USER_ID, entry.getId(), new Request(null, null, null));
	}

	void assertRejected(BudgetEntry entry, Request request, ErrorCode code) {
		int movementsBefore = stored.size();
		BigDecimal currentBefore = salary.getCurrentAmount();
		List<BigDecimal> budgetedBefore = concept.stream().map(BudgetEntry::getBudgetedAmount).toList();
		List<Boolean> manualBefore = concept.stream().map(BudgetEntry::isManual).toList();
		StoredEntryStatus statusBefore = entry.getStatus();

		assertThatThrownBy(() -> service.quickSettle(USER_ID, entry.getId(), request))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));

		// Ni el movimiento, ni la consolidación, ni el Concepto, ni las partidas de destino.
		verify(movements, never()).save(any());
		assertThat(stored).hasSize(movementsBefore);
		assertThat(entry.getStatus()).isEqualTo(statusBefore);
		assertThat(entry.getConsolidatedAt()).isNull();
		assertThat(salary.getCurrentAmount()).isEqualByComparingTo(currentBefore);
		assertThat(concept.stream().map(BudgetEntry::getBudgetedAmount).toList()).isEqualTo(budgetedBefore);
		assertThat(concept.stream().map(BudgetEntry::isManual).toList()).isEqualTo(manualBefore);
	}

	@Nested
	class Settling {

		@Test
		void anEstimatedEntryRegistersTheWholeBudgetedAmountAndIsConsolidated() {
			Result result = settle(october);

			assertThat(stored).hasSize(1);
			assertThat(result.movement().amount()).isEqualByComparingTo("1800000.00");
			assertThat(result.movement().date()).isEqualTo(TODAY);
			assertThat(result.movement().accountId()).isEqualTo(42L);
			assertThat(october.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
			assertThat(october.getConsolidatedAmount()).isEqualByComparingTo("1800000.00");
			assertThat(october.getConsolidatedAt()).isEqualTo(NOW);
			assertThat(result.consolidation().entry().amounts().actual()).isEqualByComparingTo("1800000.00");
			assertThat(result.consolidation().entry().amounts().pending()).isEqualByComparingTo("0.00");
			assertThat(result.consolidation().estimate()).isEqualByComparingTo("1800000.00");
			assertThat(result.consolidation().updatedEntries()).isZero();
			assertThat(salary.getCurrentAmount()).isEqualByComparingTo("1800000.00");
		}

		@Test
		void aPartialEntryRegistersOnlyWhatWasMissing() {
			paid(october, "500000.00");

			Result result = settle(october);

			assertThat(stored).hasSize(2);
			assertThat(result.movement().amount()).isEqualByComparingTo("1300000.00");
			assertThat(october.getConsolidatedAmount()).isEqualByComparingTo("1800000.00");
			assertThat(result.consolidation().entry().amounts().actual()).isEqualByComparingTo("1800000.00");
		}

		@Test
		void withNothingChosenUsesTodayAndTheAccountOfTheEntry() {
			Result result = settle(october);

			assertThat(result.movement().date()).isEqualTo(TODAY);
			assertThat(result.movement().accountId()).isEqualTo(bank.getId());
		}

		@Test
		void usesTheChosenDateAndAccount() {
			Result result = service.quickSettle(USER_ID, october.getId(),
					new Request(LocalDate.of(2026, 10, 3), 44L, null));

			assertThat(result.movement().date()).isEqualTo(LocalDate.of(2026, 10, 3));
			assertThat(result.movement().accountId()).isEqualTo(44L);
			assertThat(result.movement().accountName()).isEqualTo("Billetera");
		}

		@Test
		void aDateInTheEarlyWindowOfTheEntryIsValid() {
			// El período es 2026-10: la ventana de 10 días abre el 21/09, que es de un mes abierto.
			entry("2026-09", "1.00");
			Result result = service.quickSettle(USER_ID, october.getId(),
					new Request(LocalDate.of(2026, 9, 21), null, null));

			assertThat(result.movement().date()).isEqualTo(LocalDate.of(2026, 9, 21));
		}

		@Test
		void propagatesTheNewEstimateToTheFollowingMonthsLikeConsolidating() {
			november.setBudgetedAmount(new BigDecimal("1700000.00"));
			october.setBudgetedAmount(new BigDecimal("1850000.00"));

			Result result = settle(october);

			assertThat(result.movement().amount()).isEqualByComparingTo("1850000.00");
			assertThat(result.consolidation().estimate()).isEqualByComparingTo("1850000.00");
			assertThat(salary.getCurrentAmount()).isEqualByComparingTo("1850000.00");
			assertThat(november.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
			assertThat(december.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
			assertThat(january.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
			assertThat(result.consolidation().updatedEntries()).isEqualTo(3);
		}

		@Test
		void anEntryWithoutConceptIsConsolidatedWithoutPropagating() {
			BudgetEntry oneOff = entry("2026-10", "90000.00");
			concept.remove(oneOff);
			oneOff.setBudgetItem(null);
			oneOff.setOrigin(EntryOrigin.ONE_OFF);
			oneOff.setName("Venta");

			Result result = settle(oneOff);

			assertThat(result.movement().amount()).isEqualByComparingTo("90000.00");
			assertThat(result.consolidation().estimate()).isNull();
			assertThat(result.consolidation().updatedEntries()).isZero();
			assertThat(oneOff.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
			assertThat(salary.getCurrentAmount()).isEqualByComparingTo("1800000.00");
		}

		@Test
		void takesTheLockOfTheConceptBeforeReadingTheEntry() {
			settle(october);

			var order = inOrder(items, entries);
			order.verify(items).lockByUserIdAndEntryId(USER_ID, october.getId());
			order.verify(entries).findByIdAndUserIdWithDetails(october.getId(), USER_ID);
		}
	}

	@Nested
	class WhenTheMovementIsRejected {

		@Test
		void aDateBeforeTheWindow() {
			assertRejected(october, new Request(LocalDate.of(2026, 9, 20), null, null), ErrorCode.DATE_OUT_OF_RANGE);
		}

		@Test
		void aFutureDate() {
			assertRejected(october, new Request(TODAY.plusDays(1), null, null), ErrorCode.DATE_OUT_OF_RANGE);
		}

		@Test
		void aDateBeforeTheOpeningOfTheAccount() {
			assertRejected(october, new Request(LocalDate.of(2026, 10, 5), 45L, null), ErrorCode.DATE_OUT_OF_RANGE);
		}

		@Test
		void aDateInAClosedMonth() {
			BudgetPeriod september = BudgetPeriod.open(USER_ID, YearMonth.of(2026, 9));
			september.setStatus(PeriodStatus.CLOSED);
			when(periods.findByUserIdAndPeriodMonth(USER_ID, YearMonth.of(2026, 9)))
					.thenReturn(Optional.of(september));

			assertRejected(october, new Request(LocalDate.of(2026, 9, 25), null, null), ErrorCode.PERIOD_CLOSED);
		}

		@Test
		void anAccountInAnotherCurrency() {
			assertRejected(october, new Request(null, 43L, null), ErrorCode.CURRENCY_MISMATCH);
		}

		@Test
		void anAccountThatDoesNotExistIsAFieldError() {
			assertRejected(october, new Request(null, 999L, null), ErrorCode.VALIDATION_ERROR);
		}
	}

	@Nested
	class WhenTheEntryCannotBeSettled {

		@Test
		void nothingPendingInAnEstimatedEntryWithBudgetZero() {
			october.setBudgetedAmount(new BigDecimal("0.00"));

			assertRejected(october, new Request(null, null, null), ErrorCode.NOTHING_PENDING);
		}

		@Test
		void nothingPendingInACoveredPartialEntry() {
			paid(october, "1900000.00");

			assertRejected(october, new Request(null, null, null), ErrorCode.NOTHING_PENDING);
		}

		@Test
		void aConsolidatedEntry() {
			consolidated(october, "1800000.00");

			assertThatThrownBy(() -> settle(october))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING));
			verify(movements, never()).save(any());
		}

		@Test
		void aClosedPeriodWinsOverAConsolidatedEntry() {
			consolidated(october, "1800000.00");
			october.getPeriod().setStatus(PeriodStatus.CLOSED);

			assertThatThrownBy(() -> settle(october))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED));
			verify(movements, never()).save(any());
		}

		@Test
		void nothingPendingComesBeforeTheDateAndAccountErrors() {
			october.setBudgetedAmount(new BigDecimal("0.00"));

			assertRejected(october, new Request(TODAY.plusDays(5), 43L, null), ErrorCode.NOTHING_PENDING);
		}

		@Test
		void anEntryOfAnotherUserIsNotFound() {
			assertThatThrownBy(() -> service.quickSettle(OTHER_USER_ID, october.getId(), new Request(null, null, null)))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			assertThatThrownBy(() -> service.preview(OTHER_USER_ID, october.getId()))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
			verify(entries, times(2)).findByIdAndUserIdWithDetails(october.getId(), OTHER_USER_ID);
			verify(movements, never()).save(any());
		}
	}

	@Nested
	class WithEditedEntriesInTheTarget {

		@BeforeEach
		void edit() {
			december.setBudgetedAmount(new BigDecimal("2000000.00"));
			december.setManual(true);
			october.setBudgetedAmount(new BigDecimal("1850000.00"));
		}

		@Test
		void withoutAPolicyNothingIsSavedNotEvenTheMovement() {
			assertRejected(october, new Request(null, null, null), ErrorCode.MANUAL_POLICY_REQUIRED);
		}

		@Test
		void aDateErrorIsReportedBeforeTheMissingPolicy() {
			assertRejected(october, new Request(TODAY.plusDays(1), null, null), ErrorCode.DATE_OUT_OF_RANGE);
		}

		@Test
		void keepRespectsTheEditedEntry() {
			Result result = service.quickSettle(USER_ID, october.getId(),
					new Request(null, null, ManualEntriesPolicy.KEEP));

			assertThat(december.getBudgetedAmount()).isEqualByComparingTo("2000000.00");
			assertThat(december.isManual()).isTrue();
			assertThat(november.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
			assertThat(january.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
			assertThat(result.consolidation().updatedEntries()).isEqualTo(2);
			assertThat(october.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
		}

		@Test
		void overwriteReplacesTheEditedEntryAndClearsTheMark() {
			Result result = service.quickSettle(USER_ID, october.getId(),
					new Request(null, null, ManualEntriesPolicy.OVERWRITE));

			assertThat(december.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
			assertThat(december.isManual()).isFalse();
			assertThat(result.consolidation().updatedEntries()).isEqualTo(3);
		}

		@Test
		void aPolicyIsIgnoredWhenThereAreNoEditedEntries() {
			december.setManual(false);

			Result result = service.quickSettle(USER_ID, october.getId(),
					new Request(null, null, ManualEntriesPolicy.OVERWRITE));

			assertThat(result.consolidation().updatedEntries()).isEqualTo(3);
		}
	}

	@Nested
	class Previewing {

		@Test
		void showsTheMovementAndTheEstimateThatWouldResult() {
			paid(october, "500000.00");
			october.setBudgetedAmount(new BigDecimal("1850000.00"));
			november.setBudgetedAmount(new BigDecimal("1700000.00"));

			Preview preview = service.preview(USER_ID, october.getId());

			assertThat(preview.allowed()).isTrue();
			assertThat(preview.amount()).isEqualByComparingTo("1350000.00");
			assertThat(preview.consolidation().actualAmount()).isEqualByComparingTo("1850000.00");
			assertThat(preview.consolidation().estimate()).isEqualByComparingTo("1850000.00");
			assertThat(preview.consolidation().currentAmount()).isEqualByComparingTo("1800000.00");
			assertThat(preview.consolidation().targets()).hasSize(3);
		}

		@Test
		void doesNotChangeAnything() {
			october.setBudgetedAmount(new BigDecimal("1850000.00"));

			service.preview(USER_ID, october.getId());

			verify(movements, never()).save(any());
			assertThat(october.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
			assertThat(salary.getCurrentAmount()).isEqualByComparingTo("1800000.00");
			assertThat(november.getBudgetedAmount()).isEqualByComparingTo("1800000.00");
		}

		@Test
		void matchesWhatTheQuickSettleThenDoes() {
			paid(october, "500000.00");
			october.setBudgetedAmount(new BigDecimal("1850000.00"));
			november.setBudgetedAmount(new BigDecimal("1700000.00"));
			december.setBudgetedAmount(new BigDecimal("1850000.00"));
			january.setBudgetedAmount(new BigDecimal("1900000.00"));

			Preview preview = service.preview(USER_ID, october.getId());
			Result result = settle(october);

			assertThat(result.movement().amount()).isEqualByComparingTo(preview.amount());
			assertThat(october.getConsolidatedAmount()).isEqualByComparingTo(preview.consolidation().actualAmount());
			assertThat(result.consolidation().estimate()).isEqualByComparingTo(preview.consolidation().estimate());
			assertThat(salary.getCurrentAmount()).isEqualByComparingTo(preview.consolidation().estimate());
			long changing = preview.consolidation().targets().stream().filter(t -> t.changes()).count();
			assertThat(result.consolidation().updatedEntries()).isEqualTo((int) changing);
			preview.consolidation().targets().forEach(target -> {
				BudgetEntry actual = concept.stream().filter(e -> e.getId().equals(target.entryId())).findFirst()
						.orElseThrow();
				assertThat(actual.getBudgetedAmount()).isEqualByComparingTo(target.newBudgeted());
			});
		}

		@Test
		void flagsTheEditedEntriesThatRequireAPolicy() {
			december.setManual(true);

			Preview preview = service.preview(USER_ID, october.getId());

			assertThat(preview.consolidation().manualPolicyRequired()).isTrue();
		}

		@Test
		void reportsWhyItIsBlocked() {
			october.setBudgetedAmount(new BigDecimal("0.00"));
			consolidated(november, "1800000.00");
			december.getPeriod().setStatus(PeriodStatus.CLOSED);

			assertThat(service.preview(USER_ID, october.getId()).blockedBy())
					.isEqualTo(QuickSettleBlockReason.NOTHING_PENDING);
			assertThat(service.preview(USER_ID, november.getId()).blockedBy())
					.isEqualTo(QuickSettleBlockReason.ENTRY_NOT_PENDING);
			Preview closed = service.preview(USER_ID, december.getId());
			assertThat(closed.blockedBy()).isEqualTo(QuickSettleBlockReason.PERIOD_CLOSED);
			assertThat(closed.allowed()).isFalse();
			assertThat(closed.consolidation()).isNull();
			assertThat(closed.amount()).isNull();
		}
	}
}
