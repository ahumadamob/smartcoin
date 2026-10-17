package com.smartcoin.period.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.service.AccountService;
import com.smartcoin.account.service.AccountService.AccountBalance;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.category.domain.Category;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.ClosingBlockReason;
import com.smartcoin.period.domain.ClosingPlanner.EntryEffect;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.ClosingPlanner.InvalidResolution;
import com.smartcoin.period.domain.ClosingPlanner.NewEntry;
import com.smartcoin.period.domain.InvalidResolutionReason;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.ClosingService.Blocker;
import com.smartcoin.period.service.ClosingService.Preview;
import com.smartcoin.period.service.ClosingService.Resolution;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-30, HU-31, RN-38 a RN-40, RN-43 y RN-01: la simulación del cierre con repositorios simulados. El usuario tiene los
 * períodos 2026-08 a 2028-10, ninguno cerrado salvo donde se indica; el reloj, salvo donde se indica, el 16/10/2026.
 */
class ClosingServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final YearMonth START = YearMonth.of(2026, 8);
	static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
	static final YearMonth OCTOBER = YearMonth.of(2026, 10);

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	AccountService accounts = mock(AccountService.class);
	List<BudgetPeriod> existing = new ArrayList<>();
	Account bank = account(42, "Banco", Currency.ARS, "2026-08-01");
	long nextId = 1;

	@BeforeEach
	void setUp() {
		for (YearMonth month = START; !month.isAfter(YearMonth.of(2028, 10)); month = month.plusMonths(1)) {
			BudgetPeriod period = BudgetPeriod.open(USER_ID, month);
			ReflectionTestUtils.setField(period, "id", 100L + existing.size());
			existing.add(period);
			when(periods.findByUserIdAndPeriodMonth(USER_ID, month)).thenReturn(Optional.of(period));
		}
		when(periods.findPeriodMonthsByUserId(USER_ID))
				.thenReturn(existing.stream().map(BudgetPeriod::getPeriodMonth).toList());
		when(periods.findFirstByUserIdAndStatusOrderByPeriodMonthDesc(USER_ID, PeriodStatus.CLOSED))
				.thenAnswer(call -> existing.stream().filter(p -> p.getStatus() == PeriodStatus.CLOSED)
						.reduce((first, second) -> second));
		when(accounts.balancesAt(eq(USER_ID), any(LocalDate.class)))
				.thenReturn(List.of(new AccountBalance(bank, new BigDecimal("1000.00"))));
	}

	private ClosingService serviceAt(String today) {
		Clock clock = Clock.fixed(Instant.parse(today + "T15:00:00Z"), ZONE);
		return new ClosingService(periods, new PeriodViewService(periods, entries, movements, clock), accounts, clock);
	}

	private Preview preview(YearMonth month, String today) {
		return preview(month, today, List.of());
	}

	private Preview preview(YearMonth month, String today, List<Resolution> resolutions) {
		return serviceAt(today).preview(USER_ID, month, resolutions);
	}

	private BudgetPeriod period(YearMonth month) {
		return existing.stream().filter(p -> p.getPeriodMonth().equals(month)).findFirst().orElseThrow();
	}

	private void close(YearMonth... months) {
		for (YearMonth month : months) {
			period(month).setStatus(PeriodStatus.CLOSED);
			period(month).setClosedAt(Instant.parse("2026-10-01T12:00:00Z"));
		}
	}

	// Precondiciones (RN-38), con las del orden ya probadas en ClosingPlannerTest.

	@Test
	void theStartPeriodHasNoPreviousAndCanBeClosedOnceFinished() {
		Preview preview = preview(START, "2026-10-16");

		assertThat(preview.plan().preconditionsMet()).isTrue();
		assertThat(preview.blockers()).isEmpty();
		assertThat(preview.firstOpenPeriod()).isEqualTo(START);
		assertThat(preview.status()).isEqualTo(PeriodStatus.OPEN);
		verify(periods, never()).findByUserIdAndPeriodMonth(USER_ID, START.minusMonths(1));
	}

	@Test
	void withThePreviousOpenItSaysWhichMonthToCloseFirst() {
		Preview preview = preview(OCTOBER, "2026-10-16");

		assertThat(preview.blockers()).extracting(Blocker::reason)
				.containsExactly(ClosingBlockReason.PREVIOUS_PERIOD_OPEN, ClosingBlockReason.PERIOD_NOT_FINISHED);
		assertThat(preview.blockers()).extracting(Blocker::detail).containsExactly(
				"El período anterior, 2026-09, todavía está abierto. Los meses se cierran en orden: el primero "
						+ "abierto es 2026-08.",
				"El período 2026-10 termina el 31/10/2026: se puede cerrar desde ese día.");
		assertThat(preview.firstOpenPeriod()).isEqualTo(START);
	}

	@Test
	void withThePreviousClosedAndTheMonthFinishedItCanBeClosed() {
		close(START);

		Preview preview = preview(SEPTEMBER, "2026-10-16");

		assertThat(preview.plan().preconditionsMet()).isTrue();
		assertThat(preview.firstOpenPeriod()).isEqualTo(SEPTEMBER);
	}

	@Test
	void januaryLooksAtDecemberOfThePreviousYear() {
		close(existing.stream().map(BudgetPeriod::getPeriodMonth).filter(m -> m.isBefore(YearMonth.of(2027, 1)))
				.toArray(YearMonth[]::new));

		Preview preview = preview(YearMonth.of(2027, 1), "2027-01-31");

		assertThat(preview.plan().preconditionsMet()).isTrue();
		assertThat(preview.firstOpenPeriod()).isEqualTo(YearMonth.of(2027, 1));
		verify(periods).findByUserIdAndPeriodMonth(USER_ID, YearMonth.of(2026, 12));
	}

	/** Un período cerrado responde con PERIOD_CLOSED y sin listas: no lee partidas ni saldos. */
	@Test
	void aClosedPeriodHasNothingToResolve() {
		close(START);

		Preview preview = preview(START, "2026-10-16");

		assertThat(preview.status()).isEqualTo(PeriodStatus.CLOSED);
		assertThat(preview.blockers()).extracting(Blocker::reason).containsExactly(ClosingBlockReason.PERIOD_CLOSED);
		assertThat(preview.blockers().get(0).detail()).isEqualTo("El período 2026-08 ya está cerrado.");
		assertThat(preview.plan().pendingEntries()).isEmpty();
		assertThat(preview.plan().accounts()).isEmpty();
		verifyNoInteractions(entries, movements, accounts);
	}

	@Test
	void aPeriodOutsideTheRangeIsNotFound() {
		assertThatThrownBy(() -> preview(YearMonth.of(2026, 7), "2026-10-16"))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	// Partidas pendientes y cuentas (RN-39), con los montos de la vista del mes.

	private BudgetEntry entry(YearMonth month, String name, EntryKind kind, String budgeted) {
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", nextId++);
		e.setUserId(USER_ID);
		e.setPeriod(period(month));
		e.setOrigin(EntryOrigin.ONE_OFF);
		e.setName(name);
		e.setKind(kind);
		e.setAccount(bank);
		e.setDueDate(month.atDay(18));
		e.setBudgetedAmount(new BigDecimal(budgeted));
		e.setStatus(StoredEntryStatus.PENDING);
		return e;
	}

	@Test
	void pendingEntriesComeWithTheAmountsOfTheMonthView() {
		BudgetItem item = new BudgetItem();
		ReflectionTestUtils.setField(item, "id", 900L);
		item.setName("Luz");
		BudgetEntry power = entry(START, null, EntryKind.EXPENSE, "45000.00");
		power.setOrigin(EntryOrigin.RECURRING);
		power.setBudgetItem(item);
		BudgetEntry salary = entry(START, "Sueldo", EntryKind.INCOME, "1000000.00");
		salary.setDueDate(START.atDay(1));
		BudgetEntry rent = entry(START, "Alquiler", EntryKind.EXPENSE, "450000.00");
		rent.setStatus(StoredEntryStatus.CONSOLIDATED);
		rent.setConsolidatedAmount(new BigDecimal("450000.00"));
		long startId = period(START).getId();
		when(entries.findByUserIdAndPeriodIdWithDetails(USER_ID, startId)).thenReturn(List.of(power, salary, rent));
		when(movements.sumByEntryOfPeriod(USER_ID, startId)).thenReturn(List.of(
				new EntryTotal(power.getId(), new BigDecimal("20000.00")),
				new EntryTotal(rent.getId(), new BigDecimal("450000.00"))));

		Preview preview = preview(START, "2026-10-16");

		List<EntryLine> pending = preview.plan().pendingEntries();
		assertThat(pending).extracting(EntryLine::name).containsExactly("Sueldo", "Luz");
		EntryLine powerLine = pending.get(1);
		assertThat(powerLine.amounts().actual()).isEqualTo(new BigDecimal("20000.00"));
		assertThat(powerLine.amounts().pending()).isEqualTo(new BigDecimal("25000.00"));
		assertThat(powerLine.amounts().status()).isEqualTo(EntryStatus.PARTIAL);
		assertThat(pending.get(0).amounts().status()).isEqualTo(EntryStatus.ESTIMATED);
		assertThat(powerLine.accountName()).isEqualTo("Banco");
		assertThat(preview.plan().accounts()).singleElement()
				.satisfies(a -> assertThat(a.computedBalance()).isEqualTo(new BigDecimal("1000.00")));
		verify(accounts).balancesAt(USER_ID, LocalDate.of(2026, 8, 31));
	}

	// No modifica nada (criterio 4) y solo ve datos del usuario (RN-01).

	@Test
	void thePreviewSavesNothing() {
		when(entries.findByUserIdAndPeriodIdWithDetails(eq(USER_ID), anyLong()))
				.thenReturn(List.of(entry(START, "Luz", EntryKind.EXPENSE, "45000.00")));

		preview(START, "2026-10-16");
		preview(OCTOBER, "2026-10-16");

		verify(periods, never()).save(any());
		verify(periods, never()).saveAll(any());
		verify(periods, never()).saveAndFlush(any());
		verify(entries, never()).save(any());
		verify(entries, never()).saveAll(any());
		verify(entries, never()).saveAndFlush(any());
		verify(entries, never()).delete(any());
		verify(movements, never()).save(any());
		verify(movements, never()).delete(any());
		assertThat(existing).allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(PeriodStatus.OPEN));
	}

	@Test
	void anotherUsersPeriodIsNotFound() {
		when(periods.findByUserIdAndPeriodMonth(eq(OTHER_USER_ID), any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> serviceAt("2026-10-16").preview(OTHER_USER_ID, START, List.of()))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	/** Toda consulta va con el usuario actual: lo de otro usuario no puede aparecer. */
	@Test
	void everyQueryUsesTheCurrentUser() {
		preview(OCTOBER, "2026-10-16");

		long octoberId = period(OCTOBER).getId();
		verify(periods).findByUserIdAndPeriodMonth(USER_ID, OCTOBER);
		verify(periods).findByUserIdAndPeriodMonth(USER_ID, SEPTEMBER);
		verify(periods).findPeriodMonthsByUserId(USER_ID);
		verify(periods).findFirstByUserIdAndStatusOrderByPeriodMonthDesc(USER_ID, PeriodStatus.CLOSED);
		verify(entries).findByUserIdAndPeriodIdWithDetails(USER_ID, octoberId);
		verify(movements).sumByEntryOfPeriod(USER_ID, octoberId);
		verify(accounts).balancesAt(USER_ID, LocalDate.of(2026, 10, 31));
		verify(periods, never()).findByUserIdAndPeriodMonth(eq(OTHER_USER_ID), any());
		verify(entries, never()).findByUserIdAndPeriodIdWithDetails(eq(OTHER_USER_ID), anyLong());
		verify(accounts, never()).balancesAt(eq(OTHER_USER_ID), any());
	}

	// Resoluciones de las pendientes (HU-31, RN-40 pasos 1 y 2, D-43).

	/**
	 * Agosto, el período inicial: la luz recurrente (Concepto «Luz», categoría Servicios) con 20.000,00 pagados, un
	 * sueldo puntual sin movimientos y un alquiler consolidado.
	 */
	private List<BudgetEntry> august() {
		Category services = new Category();
		ReflectionTestUtils.setField(services, "id", 300L);
		services.setName("Servicios");
		BudgetItem item = new BudgetItem();
		ReflectionTestUtils.setField(item, "id", 900L);
		item.setName("Luz");
		item.setCategory(services);
		BudgetEntry power = entry(START, null, EntryKind.EXPENSE, "45000.00");
		power.setOrigin(EntryOrigin.RECURRING);
		power.setBudgetItem(item);
		BudgetEntry salary = entry(START, "Sueldo", EntryKind.INCOME, "1000000.00");
		BudgetEntry rent = entry(START, "Alquiler", EntryKind.EXPENSE, "450000.00");
		rent.setStatus(StoredEntryStatus.CONSOLIDATED);
		rent.setConsolidatedAmount(new BigDecimal("450000.00"));
		long startId = period(START).getId();
		when(entries.findByUserIdAndPeriodIdWithDetails(USER_ID, startId)).thenReturn(List.of(power, salary, rent));
		when(movements.sumByEntryOfPeriod(USER_ID, startId)).thenReturn(List.of(
				new EntryTotal(power.getId(), new BigDecimal("20000.00")),
				new EntryTotal(rent.getId(), new BigDecimal("450000.00"))));
		return List.of(power, salary, rent);
	}

	/** Con una sola resolución, la otra pendiente se informa sin resolver; la recurrente toma el Concepto. */
	@Test
	void withSomeResolutionsItSaysWhatHappensAndWhatIsMissing() {
		List<BudgetEntry> august = august();
		long powerId = august.get(0).getId();

		Preview preview = preview(START, "2026-10-16",
				List.of(new Resolution(powerId, ClosingResolution.CARRY_OVER)));

		assertThat(preview.plan().effects()).singleElement().satisfies(effect -> {
			assertThat(effect.entryId()).isEqualTo(powerId);
			assertThat(effect.consolidatedAmount()).isEqualTo(new BigDecimal("20000.00"));
		});
		NewEntry created = preview.plan().newEntries().getFirst();
		assertThat(created.period()).isEqualTo(SEPTEMBER);
		assertThat(created.name()).isEqualTo("Saldo pendiente: Luz");
		assertThat(created.categoryId()).isEqualTo(300L);
		assertThat(created.categoryName()).isEqualTo("Servicios");
		assertThat(created.accountId()).isEqualTo(42L);
		assertThat(created.dueDate()).isEqualTo(LocalDate.of(2026, 9, 18));
		assertThat(created.budgetedAmount()).isEqualTo(new BigDecimal("25000.00"));
		assertThat(created.sourceEntryId()).isEqualTo(powerId);
		assertThat(preview.plan().unresolvedEntryIds()).containsExactly(august.get(1).getId());
	}

	@Test
	void withAllResolutionsNothingIsMissing() {
		List<BudgetEntry> august = august();

		Preview preview = preview(START, "2026-10-16", List.of(
				new Resolution(august.get(1).getId(), ClosingResolution.CLOSE_AS_IS),
				new Resolution(august.get(0).getId(), ClosingResolution.CLOSE_AS_IS)));

		assertThat(preview.plan().unresolvedEntryIds()).isEmpty();
		// En el orden de la vista del mes: mismo vencimiento, «Luz» antes que «Sueldo».
		assertThat(preview.plan().effects()).extracting(EntryEffect::consolidatedAmount)
				.containsExactly(new BigDecimal("20000.00"), new BigDecimal("0.00"));
		assertThat(preview.plan().newEntries()).isEmpty();
	}

	/**
	 * Una partida de otro mes, de otro usuario o que no existe no está entre las del período: se informa igual
	 * (D-24); una consolidada del período, como no pendiente. Ninguna se aplica y no es un error (D-43).
	 */
	@Test
	void resolutionsForEntriesOutsideThePeriodOrNotPendingAreReported() {
		List<BudgetEntry> august = august();
		BudgetEntry september = entry(SEPTEMBER, "Gas", EntryKind.EXPENSE, "8000.00");
		long rentId = august.get(2).getId();

		Preview preview = preview(START, "2026-10-16", List.of(
				new Resolution(september.getId(), ClosingResolution.CARRY_OVER),
				new Resolution(rentId, ClosingResolution.CLOSE_AS_IS),
				new Resolution(5000L, ClosingResolution.CARRY_OVER)));

		assertThat(preview.plan().invalidResolutions()).containsExactly(
				new InvalidResolution(september.getId(), InvalidResolutionReason.NOT_IN_PERIOD),
				new InvalidResolution(rentId, InvalidResolutionReason.NOT_PENDING),
				new InvalidResolution(5000L, InvalidResolutionReason.NOT_IN_PERIOD));
		assertThat(preview.plan().effects()).isEmpty();
		assertThat(preview.plan().unresolvedEntryIds()).hasSize(2);
		verify(entries, never()).findByUserIdAndPeriodIdWithDetails(eq(USER_ID), eq(period(SEPTEMBER).getId()));
	}

	/** Una partida con dos resoluciones es un pedido mal formado: 400 antes de leer nada. */
	@Test
	void aRepeatedEntryIsAValidationError() {
		assertThatThrownBy(() -> preview(START, "2026-10-16", List.of(
				new Resolution(1L, ClosingResolution.CARRY_OVER),
				new Resolution(1L, ClosingResolution.CARRY_OVER))))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("resolutions");
					assertThat(e.getMessage()).isEqualTo("La partida 1 tiene más de una resolución.");
				});
		verifyNoInteractions(periods, entries, movements, accounts);
	}

	@Test
	void aClosedPeriodIgnoresTheResolutions() {
		close(START);

		Preview preview = preview(START, "2026-10-16", List.of(new Resolution(1L, ClosingResolution.CARRY_OVER)));

		assertThat(preview.plan().effects()).isEmpty();
		assertThat(preview.plan().invalidResolutions()).isEmpty();
		verifyNoInteractions(entries, movements, accounts);
	}

	/** Con resoluciones tampoco guarda nada: ni consolida ni crea partidas (HU-33). */
	@Test
	void thePreviewWithResolutionsSavesNothing() {
		List<BudgetEntry> august = august();

		preview(START, "2026-10-16", List.of(
				new Resolution(august.get(0).getId(), ClosingResolution.CARRY_OVER),
				new Resolution(august.get(1).getId(), ClosingResolution.CLOSE_AS_IS)));

		verify(entries, never()).save(any());
		verify(entries, never()).saveAll(any());
		verify(entries, never()).saveAndFlush(any());
		verify(periods, never()).save(any());
		assertThat(august).extracting(BudgetEntry::getStatus)
				.containsExactly(StoredEntryStatus.PENDING, StoredEntryStatus.PENDING, StoredEntryStatus.CONSOLIDATED);
		assertThat(august.get(0).getConsolidatedAmount()).isNull();
		assertThat(august.get(0).getClosingResolution()).isNull();
	}

	static Account account(long id, String name, Currency currency, String openingDate) {
		Account account = new Account();
		ReflectionTestUtils.setField(account, "id", id);
		account.setUserId(USER_ID);
		account.setName(name);
		account.setCurrency(currency);
		account.setOpeningDate(LocalDate.parse(openingDate));
		account.setInitialBalance(new BigDecimal("1000.00"));
		return account;
	}
}
