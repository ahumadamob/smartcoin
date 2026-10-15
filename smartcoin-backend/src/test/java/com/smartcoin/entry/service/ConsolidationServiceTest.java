package com.smartcoin.entry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.EstimationCalculator;
import com.smartcoin.entry.domain.ConsolidationBlockReason;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.entry.domain.ManualEntriesPolicy;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.entry.service.ConsolidationService.Preview;
import com.smartcoin.entry.service.ConsolidationService.Result;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-23 (RN-25 a RN-28, RN-01): sin base de datos, con repositorios simulados y el reloj fijo. Un Concepto «Sueldo»
 * de último valor con partidas de 2026-10 a 2027-02; hoy es el 8 de octubre de 2026.
 */
class ConsolidationServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");
	static final long USER_ID = 7;

	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	BudgetItemRepository items = mock(BudgetItemRepository.class);
	ConsolidationService service = new ConsolidationService(entries, movements, items, Clock.fixed(NOW, ZONE));

	Account account = new Account();
	BudgetItem salary = item(31, EstimationRule.LAST_VALUE, "1800000.00");
	List<BudgetEntry> concept = new ArrayList<>();
	long nextId = 500;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(account, "id", 42L);
		account.setCurrency(Currency.ARS);
		account.setName("Banco");
	}

	BudgetItem item(long id, EstimationRule rule, String currentAmount) {
		BudgetItem item = new BudgetItem();
		ReflectionTestUtils.setField(item, "id", id);
		item.setName("Sueldo");
		item.setEstimationRule(rule);
		item.setCurrentAmount(new BigDecimal(currentAmount));
		return item;
	}

	BudgetEntry entry(BudgetItem item, String period, String budgeted) {
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", nextId++);
		e.setUserId(USER_ID);
		e.setBudgetItem(item);
		e.setOrigin(item == null ? EntryOrigin.ONE_OFF : EntryOrigin.RECURRING);
		e.setName(item == null ? "Venta" : null);
		e.setKind(EntryKind.INCOME);
		e.setAccount(account);
		e.setBudgetedAmount(new BigDecimal(budgeted));
		e.setStatus(StoredEntryStatus.PENDING);
		BudgetPeriod p = BudgetPeriod.open(USER_ID, YearMonth.parse(period));
		p.setStatus(PeriodStatus.OPEN);
		e.setPeriod(p);
		if (item != null) {
			concept.add(e);
		}
		return e;
	}

	BudgetEntry consolidatedEntry(String period, String amount) {
		BudgetEntry e = entry(salary, period, amount);
		e.setStatus(StoredEntryStatus.CONSOLIDATED);
		e.setConsolidatedAmount(new BigDecimal(amount));
		return e;
	}

	/** Deja visibles la partida elegida y el Concepto, y le carga el total de sus movimientos. */
	void given(BudgetEntry chosen, String movementsTotal) {
		when(entries.findByIdAndUserIdWithDetails(chosen.getId(), USER_ID)).thenReturn(Optional.of(chosen));
		when(movements.sumByEntry(USER_ID, chosen.getId()))
				.thenReturn(movementsTotal == null ? null : new BigDecimal(movementsTotal));
		if (chosen.getBudgetItem() != null) {
			when(entries.findByUserIdAndBudgetItemId(USER_ID, chosen.getBudgetItem().getId())).thenReturn(concept);
		}
	}

	BudgetEntry december;
	BudgetEntry january;
	BudgetEntry february;

	/** Sueldo: diciembre se cobra distinto de lo presupuestado; enero y febrero están pendientes. */
	void salaryScenario() {
		december = entry(salary, "2026-12", "1800000.00");
		january = entry(salary, "2027-01", "1800000.00");
		february = entry(salary, "2027-02", "1800000.00");
		given(december, "1850000.00");
	}

	@Test
	void consolidatesWithTheRealAmountAndPropagatesTheSalaryExampleOfRn26() {
		salaryScenario();

		Result result = service.consolidate(USER_ID, december.getId(), null);

		assertThat(december.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
		assertThat(december.getConsolidatedAmount()).isEqualByComparingTo("1850000.00");
		assertThat(december.getConsolidatedAt()).isEqualTo(NOW);
		assertThat(salary.getCurrentAmount()).isEqualByComparingTo("1850000.00");
		assertThat(january.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
		assertThat(february.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
		assertThat(january.isManual()).isFalse();
		assertThat(result.estimate()).isEqualByComparingTo("1850000.00");
		assertThat(result.updatedEntries()).isEqualTo(2);
		assertThat(result.entry().amounts().status()).isEqualTo(EntryStatus.CONSOLIDATED);
		assertThat(result.entry().amounts().pending()).isEqualByComparingTo("0.00");
		assertThat(result.entry().amounts().forecast()).isEqualByComparingTo("1850000.00");
	}

	@Test
	void locksTheItemBeforeReadingTheEntry() {
		salaryScenario();

		service.consolidate(USER_ID, december.getId(), null);

		var order = org.mockito.Mockito.inOrder(items, entries);
		order.verify(items).lockByUserIdAndEntryId(USER_ID, december.getId());
		order.verify(entries).findByIdAndUserIdWithDetails(december.getId(), USER_ID);
	}

	@Test
	void usesTheAverageOfTheLastThreeWhenTheItemSaysSo() {
		BudgetItem light = item(32, EstimationRule.AVERAGE_LAST_3, "45000.00");
		salary = light;
		consolidatedEntry("2026-07", "50000.00");
		consolidatedEntry("2026-08", "41200.00");
		consolidatedEntry("2026-09", "38750.00");
		BudgetEntry october = entry(light, "2026-10", "45000.00");
		BudgetEntry november = entry(light, "2026-11", "45000.00");
		given(october, "45310.00");

		service.consolidate(USER_ID, october.getId(), null);

		assertThat(light.getCurrentAmount()).isEqualByComparingTo("41753.33");
		assertThat(november.getBudgetedAmount()).isEqualByComparingTo("41753.33");
	}

	@Test
	void thePreviewOfTheAverageReportsTheConsolidationsThatEnterAndMatchesTheConsolidation() {
		BudgetItem light = item(32, EstimationRule.AVERAGE_LAST_3, "45000.00");
		salary = light;
		// la de julio y la de cierre de septiembre no entran: el promedio es de agosto, octubre y noviembre
		consolidatedEntry("2026-07", "50000.00");
		consolidatedEntry("2026-08", "41200.00");
		BudgetEntry closing = consolidatedEntry("2026-09", "0.00");
		closing.setClosingResolution(ClosingResolution.CLOSE_AS_IS);
		consolidatedEntry("2026-10", "38750.00");
		BudgetEntry november = entry(light, "2026-11", "45000.00");
		BudgetEntry december = entry(light, "2026-12", "45000.00");
		given(november, "45310.00");

		Preview preview = service.preview(USER_ID, november.getId());

		assertThat(preview.estimateBasis()).extracting(EstimationCalculator.BaseEntry::period)
				.containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 10), YearMonth.of(2026, 8));
		assertThat(preview.estimateBasis().get(0).amount()).isEqualByComparingTo("45310.00");
		assertThat(preview.estimate()).isEqualByComparingTo("41753.33");

		Result result = service.consolidate(USER_ID, november.getId(), null);

		assertThat(result.estimate()).isEqualByComparingTo(preview.estimate());
		assertThat(december.getBudgetedAmount()).isEqualByComparingTo(preview.targets().get(0).newBudgeted());
	}

	@Test
	void theBasisHasAsManyConsolidationsAsThereAreUpToThree() {
		BudgetItem light = item(32, EstimationRule.AVERAGE_LAST_3, "45000.00");
		salary = light;
		consolidatedEntry("2026-09", "100.00");
		BudgetEntry october = entry(light, "2026-10", "45000.00");
		given(october, "200.00");

		Preview preview = service.preview(USER_ID, october.getId());

		assertThat(preview.estimateBasis()).hasSize(2);
		assertThat(preview.estimate()).isEqualByComparingTo("150.00");
	}

	@Test
	void anEntryWithoutAnItemConsolidatesAndDoesNotPropagate() {
		BudgetEntry oneOff = entry(null, "2026-11", "70000.00");
		given(oneOff, "65000.00");

		Result result = service.consolidate(USER_ID, oneOff.getId(), null);

		assertThat(oneOff.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
		assertThat(oneOff.getConsolidatedAmount()).isEqualByComparingTo("65000.00");
		assertThat(result.estimate()).isNull();
		assertThat(result.updatedEntries()).isZero();
		verify(entries, never()).findByUserIdAndBudgetItemId(anyLong(), anyLong());
	}

	@Test
	void keepsTheEditedEntriesWithKeepAndTheVisaExampleOfRn26() {
		BudgetItem visa = item(33, EstimationRule.LAST_VALUE, "200000.00");
		salary = visa;
		BudgetEntry november = entry(visa, "2026-11", "200000.00");
		BudgetEntry dec = entry(visa, "2026-12", "300000.00");
		dec.setManual(true);
		BudgetEntry jan = entry(visa, "2027-01", "200000.00");
		given(november, "210000.00");

		Result result = service.consolidate(USER_ID, november.getId(), ManualEntriesPolicy.KEEP);

		assertThat(dec.getBudgetedAmount()).isEqualByComparingTo("300000.00");
		assertThat(dec.isManual()).isTrue();
		assertThat(jan.getBudgetedAmount()).isEqualByComparingTo("210000.00");
		assertThat(visa.getCurrentAmount()).isEqualByComparingTo("210000.00");
		assertThat(result.updatedEntries()).isEqualTo(1);
	}

	@Test
	void overwritesTheEditedEntriesWithOverwrite() {
		BudgetItem visa = item(33, EstimationRule.LAST_VALUE, "200000.00");
		salary = visa;
		BudgetEntry november = entry(visa, "2026-11", "200000.00");
		BudgetEntry dec = entry(visa, "2026-12", "300000.00");
		dec.setManual(true);
		BudgetEntry jan = entry(visa, "2027-01", "200000.00");
		given(november, "210000.00");

		service.consolidate(USER_ID, november.getId(), ManualEntriesPolicy.OVERWRITE);

		assertThat(dec.getBudgetedAmount()).isEqualByComparingTo("210000.00");
		assertThat(dec.isManual()).isFalse();
		assertThat(jan.getBudgetedAmount()).isEqualByComparingTo("210000.00");
	}

	@Test
	void withoutPolicyAndWithEditedEntriesNothingChanges() {
		BudgetItem visa = item(33, EstimationRule.LAST_VALUE, "200000.00");
		salary = visa;
		BudgetEntry november = entry(visa, "2026-11", "200000.00");
		BudgetEntry dec = entry(visa, "2026-12", "300000.00");
		dec.setManual(true);
		BudgetEntry jan = entry(visa, "2027-01", "200000.00");
		given(november, "210000.00");

		assertThatThrownBy(() -> service.consolidate(USER_ID, november.getId(), null))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.MANUAL_POLICY_REQUIRED);
					assertThat(e.entries()).containsExactly(dec.getId());
				});

		assertThat(november.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(november.getConsolidatedAmount()).isNull();
		assertThat(november.getConsolidatedAt()).isNull();
		assertThat(visa.getCurrentAmount()).isEqualByComparingTo("200000.00");
		assertThat(dec.getBudgetedAmount()).isEqualByComparingTo("300000.00");
		assertThat(jan.getBudgetedAmount()).isEqualByComparingTo("200000.00");
	}

	@Test
	void aPolicyThatIsNotNeededIsIgnored() {
		salaryScenario();

		service.consolidate(USER_ID, december.getId(), ManualEntriesPolicy.OVERWRITE);

		assertThat(december.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
		assertThat(january.getBudgetedAmount()).isEqualByComparingTo("1850000.00");

		BudgetEntry oneOff = entry(null, "2026-11", "70000.00");
		given(oneOff, "65000.00");
		service.consolidate(USER_ID, oneOff.getId(), ManualEntriesPolicy.KEEP);
		assertThat(oneOff.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
	}

	@Test
	void consolidatingAnEarlierMonthAfterALaterOneDoesNotTouchTheLaterMonth() {
		BudgetEntry october = entry(salary, "2026-10", "1800000.00");
		BudgetEntry november = consolidatedEntry("2026-11", "1900000.00");
		BudgetEntry dec = entry(salary, "2026-12", "1900000.00");
		salary.setCurrentAmount(new BigDecimal("1900000.00"));
		given(october, "1850000.00");

		Result result = service.consolidate(USER_ID, october.getId(), null);

		assertThat(november.getConsolidatedAmount()).isEqualByComparingTo("1900000.00");
		assertThat(november.getBudgetedAmount()).isEqualByComparingTo("1900000.00");
		assertThat(salary.getCurrentAmount()).isEqualByComparingTo("1900000.00");
		assertThat(dec.getBudgetedAmount()).isEqualByComparingTo("1900000.00");
		assertThat(result.estimate()).isEqualByComparingTo("1900000.00");
		assertThat(result.updatedEntries()).isZero();
		assertThat(october.getConsolidatedAmount()).isEqualByComparingTo("1850000.00");
	}

	@Test
	void closingConsolidationsAreNotTheBase() {
		BudgetEntry closing = consolidatedEntry("2026-11", "0.00");
		closing.setClosingResolution(ClosingResolution.CLOSE_AS_IS);
		BudgetEntry october = entry(salary, "2026-10", "1800000.00");
		BudgetEntry dec = entry(salary, "2026-12", "1800000.00");
		given(october, "1850000.00");

		service.consolidate(USER_ID, october.getId(), null);

		// La base es octubre (la de noviembre no cuenta) y diciembre es posterior.
		assertThat(salary.getCurrentAmount()).isEqualByComparingTo("1850000.00");
		assertThat(dec.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
	}

	@Test
	void doesNotTouchPendingEntriesOfClosedPeriods() {
		salaryScenario();
		january.getPeriod().setStatus(PeriodStatus.CLOSED);

		service.consolidate(USER_ID, december.getId(), null);

		assertThat(january.getBudgetedAmount()).isEqualByComparingTo("1800000.00");
		assertThat(february.getBudgetedAmount()).isEqualByComparingTo("1850000.00");
	}

	@Test
	void theLastInstallmentEndsThePropagationBecauseThereAreNoLaterEntries() {
		BudgetItem fridge = item(34, EstimationRule.LAST_VALUE, "10000.00");
		fridge.setInstallmentsTotal(3);
		salary = fridge;
		BudgetEntry first = entry(fridge, "2026-11", "10000.00");
		BudgetEntry second = entry(fridge, "2026-12", "10000.00");
		BudgetEntry third = entry(fridge, "2027-01", "10000.00");
		given(first, "10500.00");

		Result result = service.consolidate(USER_ID, first.getId(), null);

		assertThat(second.getBudgetedAmount()).isEqualByComparingTo("10500.00");
		assertThat(third.getBudgetedAmount()).isEqualByComparingTo("10500.00");
		assertThat(result.updatedEntries()).isEqualTo(2);
	}

	@Test
	void anEntryWithoutMovementsCannotBeConsolidated() {
		salaryScenario();
		given(december, null);

		assertThatThrownBy(() -> service.consolidate(USER_ID, december.getId(), null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.CONSOLIDATION_REQUIRES_MOVEMENT));
		assertThat(december.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(january.getBudgetedAmount()).isEqualByComparingTo("1800000.00");
	}

	@Test
	void aConsolidatedEntryIsNotPending() {
		salaryScenario();
		december.setStatus(StoredEntryStatus.CONSOLIDATED);
		december.setConsolidatedAmount(new BigDecimal("1850000.00"));

		assertThatThrownBy(() -> service.consolidate(USER_ID, december.getId(), null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING));
	}

	@Test
	void aClosedPeriodWinsOverTheStatusAndTheMovements() {
		salaryScenario();
		december.getPeriod().setStatus(PeriodStatus.CLOSED);
		december.setStatus(StoredEntryStatus.CONSOLIDATED);
		given(december, null);

		assertThatThrownBy(() -> service.consolidate(USER_ID, december.getId(), null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED));
	}

	@Test
	void theStatusWinsOverTheMissingMovements() {
		salaryScenario();
		december.setStatus(StoredEntryStatus.CONSOLIDATED);
		given(december, null);

		assertThatThrownBy(() -> service.consolidate(USER_ID, december.getId(), null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_NOT_PENDING));
	}

	@Test
	void theRequirementOnMovementsWinsOverThePolicy() {
		salaryScenario();
		january.setManual(true);
		given(december, null);

		assertThatThrownBy(() -> service.consolidate(USER_ID, december.getId(), null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.CONSOLIDATION_REQUIRES_MOVEMENT));
	}

	@Test
	void anotherUsersEntryIsNotFound() {
		when(entries.findByIdAndUserIdWithDetails(900L, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.consolidate(USER_ID, 900, null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> service.preview(USER_ID, 900)).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
		verify(entries, never()).findByUserIdAndBudgetItemId(anyLong(), anyLong());
	}

	@Test
	void queriesAreAlwaysScopedToTheCurrentUser() {
		salaryScenario();

		service.preview(USER_ID, december.getId());

		verify(entries).findByIdAndUserIdWithDetails(december.getId(), USER_ID);
		verify(entries).findByUserIdAndBudgetItemId(USER_ID, salary.getId());
		verify(movements).sumByEntry(USER_ID, december.getId());
	}

	@Test
	void thePreviewReportsTheSameThingsTheConsolidationDoes() {
		salaryScenario();
		january.setBudgetedAmount(new BigDecimal("1850000.00"));

		Preview preview = service.preview(USER_ID, december.getId());

		assertThat(preview.allowed()).isTrue();
		assertThat(preview.recurring()).isTrue();
		assertThat(preview.actualAmount()).isEqualByComparingTo("1850000.00");
		assertThat(preview.estimate()).isEqualByComparingTo("1850000.00");
		assertThat(preview.currentAmount()).isEqualByComparingTo("1800000.00");
		assertThat(preview.estimationRule()).isEqualTo(EstimationRule.LAST_VALUE);
		assertThat(preview.manualPolicyRequired()).isFalse();
		assertThat(preview.targets()).hasSize(2);
		assertThat(preview.targets().get(0).period()).isEqualTo(YearMonth.of(2027, 1));
		assertThat(preview.targets().get(0).changes()).isFalse();
		assertThat(preview.targets().get(1).changes()).isTrue();

		Result result = service.consolidate(USER_ID, december.getId(), null);

		assertThat(result.estimate()).isEqualByComparingTo(preview.estimate());
		assertThat(result.updatedEntries()).isEqualTo(
				preview.targets().stream().filter(t -> t.changes()).count());
	}

	@Test
	void thePreviewChangesNothing() {
		salaryScenario();

		service.preview(USER_ID, december.getId());

		assertThat(december.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(december.getConsolidatedAmount()).isNull();
		assertThat(december.getConsolidatedAt()).isNull();
		assertThat(salary.getCurrentAmount()).isEqualByComparingTo("1800000.00");
		assertThat(january.getBudgetedAmount()).isEqualByComparingTo("1800000.00");
		verify(items, never()).lockByUserIdAndEntryId(anyLong(), anyLong());
		verify(entries, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void thePreviewIndicatesThatThePolicyIsNeededAndWhichTargetsAreEdited() {
		salaryScenario();
		january.setManual(true);

		Preview preview = service.preview(USER_ID, december.getId());

		assertThat(preview.manualPolicyRequired()).isTrue();
		assertThat(preview.targets().get(0).manual()).isTrue();
		assertThat(preview.targets().get(1).manual()).isFalse();
	}

	@Test
	void thePreviewOfAnEntryThatCannotBeConsolidatedSaysWhy() {
		salaryScenario();
		given(december, null);

		Preview withoutMovements = service.preview(USER_ID, december.getId());

		assertThat(withoutMovements.allowed()).isFalse();
		assertThat(withoutMovements.blockedBy()).isEqualTo(ConsolidationBlockReason.CONSOLIDATION_REQUIRES_MOVEMENT);
		assertThat(withoutMovements.blockedDetail()).isNotBlank();
		assertThat(withoutMovements.estimate()).isNull();
		assertThat(withoutMovements.targets()).isEmpty();
		assertThat(withoutMovements.actualAmount()).isEqualByComparingTo("0.00");

		december.setStatus(StoredEntryStatus.CONSOLIDATED);
		assertThat(service.preview(USER_ID, december.getId()).blockedBy())
				.isEqualTo(ConsolidationBlockReason.ENTRY_NOT_PENDING);

		december.getPeriod().setStatus(PeriodStatus.CLOSED);
		assertThat(service.preview(USER_ID, december.getId()).blockedBy())
				.isEqualTo(ConsolidationBlockReason.PERIOD_CLOSED);
	}

	@Test
	void thePreviewOfAnEntryWithoutAnItemHasNoPlan() {
		BudgetEntry oneOff = entry(null, "2026-11", "70000.00");
		given(oneOff, "65000.00");

		Preview preview = service.preview(USER_ID, oneOff.getId());

		assertThat(preview.allowed()).isTrue();
		assertThat(preview.recurring()).isFalse();
		assertThat(preview.actualAmount()).isEqualByComparingTo("65000.00");
		assertThat(preview.estimate()).isNull();
		assertThat(preview.targets()).isEmpty();
		assertThat(preview.manualPolicyRequired()).isFalse();
	}

	@Test
	void anErrorWhileApplyingLeavesNothingChangedBecauseItAllRunsInOneTransaction() throws Exception {
		// El servicio no guarda en pasos: modifica entidades gestionadas dentro de una sola transacción. Un error
		// después de decidir no persiste nada; se verifica que la transacción es una sola y que no hay saves.
		var method = ConsolidationService.class.getMethod("consolidate", long.class, long.class,
				ManualEntriesPolicy.class);
		assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
		assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly())
				.isFalse();
		salaryScenario();
		given(december, null);
		assertThatThrownBy(() -> service.consolidate(USER_ID, december.getId(), null))
				.isInstanceOf(BusinessException.class);
		verify(entries, never()).save(org.mockito.ArgumentMatchers.any());
		assertThat(january.getBudgetedAmount()).isEqualByComparingTo("1800000.00");
	}
}
