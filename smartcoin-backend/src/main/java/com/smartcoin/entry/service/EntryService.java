package com.smartcoin.entry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.DeletionScope;
import com.smartcoin.entry.domain.EntryDeletionPlanner;
import com.smartcoin.entry.domain.EntryDeletionPlanner.BlockReason;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Blocker;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Candidate;
import com.smartcoin.entry.domain.EntryDeletionPlanner.ItemFacts;
import com.smartcoin.entry.domain.EntryDeletionPlanner.ItemOutcome;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Plan;
import com.smartcoin.entry.domain.EntryDueDateRange;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partidas: alta de las puntuales y edición (HU-16, HU-17, RN-18, RN-19) y eliminación con alcance (HU-18, RN-30 a
 * RN-32). Toda consulta lleva el {@code userId} del usuario actual.
 *
 * <p>El alta y la edición no crean ni modifican otra partida ni un Concepto: no se asegura el horizonte (RN-07 es de
 * los Conceptos) y una partida puntual no se copia a otros períodos (RN-19). Eliminar sí puede fijar el fin de un
 * Concepto o eliminarlo, en la misma transacción que sus partidas.
 */
@Service
public class EntryService {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/** Datos de una partida puntual nueva. El formato de cada uno ya lo controló Bean Validation. */
	public record NewOneOff(String name, EntryKind kind, Long accountId, Long categoryId, LocalDate dueDate,
			BigDecimal budgetedAmount) {
	}

	/**
	 * Cambios de una partida. Un campo {@code null} significa «no cambia»; para vaciar la categoría se usa
	 * {@code clearCategory}, porque {@code null} no distingue «no enviado» de «vaciar». Enviar el mismo valor que ya
	 * tiene la partida no es un cambio.
	 */
	public record Changes(String name, EntryKind kind, Long accountId, Long categoryId, boolean clearCategory,
			LocalDate dueDate, BigDecimal budgetedAmount) {
	}

	private final BudgetPeriodRepository periods;
	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final AccountRepository accounts;
	private final CategoryRepository categories;
	private final BudgetItemRepository items;
	private final Clock clock;

	public EntryService(BudgetPeriodRepository periods, BudgetEntryRepository entries, MovementRepository movements,
			AccountRepository accounts, CategoryRepository categories, BudgetItemRepository items, Clock clock) {
		this.periods = periods;
		this.entries = entries;
		this.movements = movements;
		this.accounts = accounts;
		this.categories = categories;
		this.items = items;
		this.clock = clock;
	}

	/**
	 * Lo que pasaría al eliminar una partida, para mostrarlo antes de confirmar. Una partida sin Concepto trae solo
	 * {@code removal}; una recurrente, {@code onlyThis} y {@code thisAndFuture}. Es la misma regla que la
	 * eliminación (HU-18): si un plan dice que se puede, la eliminación lo hace.
	 */
	public record DeletionPreview(long entryId, boolean recurring, EntryOrigin origin, YearMonth period,
			Plan removal, Plan onlyThis, Plan thisAndFuture) {
	}

	/**
	 * Agrega una partida puntual a un período abierto (RN-19). Orden: el período (404 si no existe para el usuario,
	 * RN-06; 409 si está cerrado, RN-09) y después lo que está mal en el cuerpo (400: el vencimiento, la cuenta y la
	 * categoría, que no existen o son de otro usuario, D-24). La respuesta tiene la forma de la vista del mes.
	 */
	@Transactional
	public EntryRow createOneOff(long userId, YearMonth periodMonth, NewOneOff values) {
		BudgetPeriod period = periods.findByUserIdAndPeriodMonth(userId, periodMonth)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "El período " + periodMonth
						+ " no existe: es anterior al período inicial o posterior al horizonte."));
		verifyOpen(period);
		verifyDueDate(periodMonth, values.dueDate());
		Account account = findAccount(userId, values.accountId());
		Category category = values.categoryId() == null ? null : findCategory(userId, values.categoryId());

		BudgetEntry entry = new BudgetEntry();
		entry.setUserId(userId);
		entry.setPeriod(period);
		entry.setOrigin(EntryOrigin.ONE_OFF);
		entry.setName(values.name().strip());
		entry.setKind(values.kind());
		entry.setCategory(category);
		entry.setAccount(account);
		entry.setDueDate(values.dueDate());
		entry.setBudgetedAmount(amount(values.budgetedAmount()));
		entry.setManual(false);
		entry.setStatus(StoredEntryStatus.PENDING);
		BudgetEntry saved = entries.save(entry);
		// Recién creada: no tiene movimientos.
		return PeriodViewService.row(saved, null, LocalDate.now(clock));
	}

	/**
	 * Edita una partida pendiente de un período abierto (RN-18). Orden: lo que está mal en el pedido mismo (400: una
	 * categoría y a la vez vaciarla); la partida (404 si no existe o es de otro usuario, RN-01); su estado (409
	 * {@code PERIOD_CLOSED} y después {@code ENTRY_NOT_PENDING}: en un período cerrado todas están consolidadas, y el
	 * motivo útil es el mes); lo que no se edita (409 {@code FIELD_NOT_EDITABLE}); lo que está mal en el cuerpo (400:
	 * vencimiento, cuenta y categoría); y por último la moneda con movimientos (409 {@code CURRENCY_MISMATCH}). Con un
	 * pedido rechazado no cambia nada.
	 *
	 * <p>Las partidas recurrentes toman sus datos del Concepto: cualquier cambio salvo el presupuestado es 409
	 * {@code FIELD_NOT_EDITABLE}, y un cuerpo que mezcla el presupuestado con otro cambio se rechaza entero (HU-17).
	 * Cambiar el presupuestado de una recurrente la marca como editada, y queda así aunque vuelva al monto vigente;
	 * enviar el mismo monto no es un cambio y no la marca. En las que no tienen Concepto nunca se marca: no se
	 * propaga (RN-18). Editar no toca otras partidas ni el Concepto (D-11).
	 */
	@Transactional
	public EntryRow update(long userId, long id, Changes changes) {
		if (changes.categoryId() != null && changes.clearCategory()) {
			throw BusinessException.invalidField("clearCategory",
					"No se puede indicar una categoría y pedir que se vacíe a la vez.");
		}
		BudgetEntry entry = entries.findByIdAndUserIdWithDetails(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));
		BudgetPeriod period = entry.getPeriod();
		verifyOpen(period);
		if (entry.getStatus() != StoredEntryStatus.PENDING) {
			throw new BusinessException(ErrorCode.ENTRY_NOT_PENDING,
					"La partida está consolidada: no se puede editar. Desconsolidala primero.");
		}
		verifyEditable(entry, changes);

		// Van en el cuerpo, no en la ruta: una referencia inexistente o ajena es un dato inválido (D-24).
		if (changes.dueDate() != null) {
			verifyDueDate(period.getPeriodMonth(), changes.dueDate());
		}
		Account account = changes.accountId() == null ? null : findAccount(userId, changes.accountId());
		Category category = changes.categoryId() == null ? null : findCategory(userId, changes.categoryId());
		if (account != null && !account.getId().equals(entry.getAccount().getId())) {
			verifyCurrencyWithMovements(userId, entry, account);
		}

		if (changes.name() != null) {
			entry.setName(changes.name().strip());
		}
		if (account != null) {
			entry.setAccount(account);
		}
		if (category != null) {
			entry.setCategory(category);
		}
		else if (changes.clearCategory()) {
			entry.setCategory(null);
		}
		if (changes.dueDate() != null) {
			entry.setDueDate(changes.dueDate());
		}
		if (changes.budgetedAmount() != null
				&& changes.budgetedAmount().compareTo(entry.getBudgetedAmount()) != 0) {
			entry.setBudgetedAmount(amount(changes.budgetedAmount()));
			// Solo una recurrente queda editada: la marca existe para que el monto vigente no la pise (RN-15, RN-18).
			if (entry.getBudgetItem() != null) {
				entry.setManual(true);
			}
		}
		return PeriodViewService.row(entry, movements.sumByEntry(userId, entry.getId()), LocalDate.now(clock));
	}

	/**
	 * Elimina una partida pendiente y sin movimientos de un período abierto (RN-30, RN-31, RN-32). Una recurrente
	 * exige el alcance: {@code ONLY_THIS} elimina solo esa partida y el Concepto y su {@code generated_until} no
	 * cambian, así la partida no reaparece (RN-13); {@code THIS_AND_FUTURE} elimina esa y las posteriores del
	 * Concepto y fija su fin en el mes anterior. Si el Concepto queda sin ninguna partida y sin nada por generar,
	 * también se elimina. Una partida sin Concepto no lleva alcance.
	 *
	 * <p>Orden de los errores: la partida (404 si no existe o es de otro usuario, RN-01); el alcance (400 en
	 * {@code scope}: falta en una recurrente o sobra en una sin Concepto); el período de la partida elegida (409
	 * {@code PERIOD_CLOSED}); y las partidas del alcance que impiden (409 {@code ENTRY_NOT_PENDING} si alguna está
	 * consolidada, si no {@code ENTRY_HAS_MOVEMENTS}, con sus ids en {@code entries}). Todo o nada: con un
	 * impedimento no se elimina ni se modifica nada.
	 *
	 * <p>Sin una consulta por partida: las del Concepto se leen juntas, los movimientos se consultan una vez para
	 * las candidatas y las partidas se eliminan con una sola sentencia. Primero las partidas y después el Concepto:
	 * las claves foráneas son {@code RESTRICT}.
	 */
	@Transactional
	public void delete(long userId, long id, DeletionScope scope) {
		BudgetEntry entry = findWithDetails(userId, id);
		BudgetItem item = entry.getBudgetItem();
		if (item != null && scope == null) {
			throw BusinessException.invalidField("scope",
					"Indicá si querés eliminar solo este mes o este mes y los siguientes.");
		}
		if (item == null && scope != null) {
			throw BusinessException.invalidField("scope",
					"Una partida sin Concepto no tiene alcance: se elimina sola.");
		}
		verifyOpen(entry.getPeriod());

		List<Candidate> candidates = candidates(userId, entry);
		Plan plan = item == null ? EntryDeletionPlanner.forEntryWithoutItem(candidates.getFirst())
				: EntryDeletionPlanner.forRecurring(scope, entry.getId(), facts(item), candidates);
		if (!plan.allowed()) {
			throw blocked(plan, entry.getId());
		}

		List<Long> ids = plan.toDeleteIds();
		int deleted = entries.deleteByUserIdAndIdIn(userId, ids);
		if (deleted != ids.size()) {
			// Otra operación cambió las partidas entre la lectura y el borrado: se deshace todo.
			throw new IllegalStateException("Se esperaba eliminar " + ids.size() + " partidas y se eliminaron "
					+ deleted + ".");
		}
		if (plan.itemOutcome() == ItemOutcome.REMOVES_ITEM) {
			items.deleteByUserIdAndId(userId, item.getId());
		}
		else if (plan.itemOutcome() == ItemOutcome.ENDS_ITEM) {
			// El Concepto es una entidad gestionada: se guarda al confirmar. generated_until no cambia (D-09).
			item.setEndPeriod(plan.newEndPeriod());
		}
	}

	/**
	 * Vista previa de la eliminación (HU-18): qué partidas se eliminarían con cada alcance, cuáles lo impiden y cómo
	 * queda el Concepto. No modifica nada. Errores: partida inexistente o ajena (404) y período cerrado (409).
	 */
	@Transactional(readOnly = true)
	public DeletionPreview deletionPreview(long userId, long id) {
		BudgetEntry entry = findWithDetails(userId, id);
		verifyOpen(entry.getPeriod());
		BudgetItem item = entry.getBudgetItem();
		List<Candidate> candidates = candidates(userId, entry);
		YearMonth period = entry.getPeriod().getPeriodMonth();
		if (item == null) {
			return new DeletionPreview(id, false, entry.getOrigin(), period,
					EntryDeletionPlanner.forEntryWithoutItem(candidates.getFirst()), null, null);
		}
		ItemFacts facts = facts(item);
		return new DeletionPreview(id, true, entry.getOrigin(), period, null,
				EntryDeletionPlanner.forRecurring(DeletionScope.ONLY_THIS, id, facts, candidates),
				EntryDeletionPlanner.forRecurring(DeletionScope.THIS_AND_FUTURE, id, facts, candidates));
	}

	private BudgetEntry findWithDetails(long userId, long id) {
		return entries.findByIdAndUserIdWithDetails(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));
	}

	private static ItemFacts facts(BudgetItem item) {
		return new ItemFacts(item.getEndPeriod(), item.getGeneratedUntil());
	}

	/**
	 * Las partidas a considerar: la elegida sola si no tiene Concepto, o todas las del Concepto (una consulta). Los
	 * movimientos se consultan una sola vez y solo para las pendientes con período igual o posterior al de la elegida,
	 * que son las únicas que pueden entrar en un alcance. La primera de la lista de una partida sin Concepto es ella.
	 */
	private List<Candidate> candidates(long userId, BudgetEntry chosen) {
		BudgetItem item = chosen.getBudgetItem();
		List<BudgetEntry> all = item == null ? List.of(chosen)
				: entries.findByUserIdAndBudgetItemId(userId, item.getId());
		YearMonth from = chosen.getPeriod().getPeriodMonth();
		List<Long> toCheck = all.stream().filter(entry -> entry.getStatus() == StoredEntryStatus.PENDING
				&& !entry.getPeriod().getPeriodMonth().isBefore(from)).map(BudgetEntry::getId).toList();
		Set<Long> withMovements = toCheck.isEmpty() ? Set.of()
				: new HashSet<>(movements.findEntryIdsWithMovements(userId, toCheck));
		return all.stream().map(entry -> new Candidate(entry.getId(), entry.getPeriod().getPeriodMonth(),
				entry.getStatus() == StoredEntryStatus.CONSOLIDATED, withMovements.contains(entry.getId()))).toList();
	}

	/** RN-31, RN-32: el código lo da la más fuerte de las razones (consolidada antes que movimientos). */
	private static BusinessException blocked(Plan plan, long chosenId) {
		ErrorCode code = plan.primaryReason() == BlockReason.CONSOLIDATED ? ErrorCode.ENTRY_NOT_PENDING
				: ErrorCode.ENTRY_HAS_MOVEMENTS;
		return new BusinessException(code, blockedDetail(plan, chosenId), plan.blockerIds());
	}

	private static String blockedDetail(Plan plan, long chosenId) {
		if (plan.blockers().size() == 1) {
			Blocker blocker = plan.blockers().getFirst();
			boolean chosen = blocker.entryId() == chosenId;
			String which = chosen ? "La partida" : "La partida de " + blocker.period();
			String why = blocker.reason() == BlockReason.CONSOLIDATED
					? " está consolidada: no se puede eliminar."
					: " tiene movimientos: para eliminarla, eliminá primero sus movimientos.";
			return which + why + (chosen ? "" : " No se eliminó nada.");
		}
		long consolidated = plan.count(BlockReason.CONSOLIDATED);
		long withMovements = plan.count(BlockReason.HAS_MOVEMENTS);
		String parts = consolidated > 0 && withMovements > 0
				? count(consolidated, "está consolidada", "están consolidadas") + " y "
						+ count(withMovements, "tiene movimientos", "tienen movimientos")
				: consolidated > 0 ? count(consolidated, "está consolidada", "están consolidadas")
						: count(withMovements, "tiene movimientos", "tienen movimientos");
		return "No se eliminó nada: " + parts + ".";
	}

	private static String count(long n, String singular, String plural) {
		return n == 1 ? "1 partida " + singular : n + " partidas " + plural;
	}

	/** RN-18: una recurrente no se edita acá; el tipo de una partida no se edita nunca. */
	private static void verifyEditable(BudgetEntry entry, Changes changes) {
		if (entry.getBudgetItem() != null) {
			if (changesSomething(entry, changes)) {
				throw new BusinessException(ErrorCode.FIELD_NOT_EDITABLE,
						"Los datos de una partida recurrente se editan en su Concepto.");
			}
			return;
		}
		if (changes.kind() != null && changes.kind() != entry.getKind()) {
			throw new BusinessException(ErrorCode.FIELD_NOT_EDITABLE,
					"El tipo no se puede cambiar: para cambiarlo, eliminá la partida y creá otra.");
		}
	}

	/** Si el pedido cambia algún dato de una recurrente que no sea el presupuestado, el único propio de la partida. */
	private static boolean changesSomething(BudgetEntry entry, Changes changes) {
		Long currentCategoryId = entry.getBudgetItem().getCategory() == null ? null
				: entry.getBudgetItem().getCategory().getId();
		return changes.name() != null && !changes.name().strip().equals(entry.getBudgetItem().getName())
				|| changes.kind() != null && changes.kind() != entry.getKind()
				|| changes.accountId() != null && !changes.accountId().equals(entry.getAccount().getId())
				|| changes.categoryId() != null && !changes.categoryId().equals(currentCategoryId)
				|| changes.clearCategory() && currentCategoryId != null
				|| changes.dueDate() != null && !changes.dueDate().equals(entry.getDueDate());
	}

	/** RN-19: con movimientos, la cuenta solo cambia por otra de la misma moneda. Sin movimientos es libre (D-30). */
	private void verifyCurrencyWithMovements(long userId, BudgetEntry entry, Account target) {
		if (Objects.equals(target.getCurrency(), entry.getAccount().getCurrency())) {
			return;
		}
		if (!movements.findEntryIdsWithMovements(userId, List.of(entry.getId())).isEmpty()) {
			throw new BusinessException(ErrorCode.CURRENCY_MISMATCH, "La partida ya tiene movimientos: su cuenta solo "
					+ "puede cambiarse por otra en " + entry.getAccount().getCurrency() + ".");
		}
	}

	private static void verifyOpen(BudgetPeriod period) {
		if (period.getStatus() == PeriodStatus.CLOSED) {
			throw new BusinessException(ErrorCode.PERIOD_CLOSED,
					"El período " + period.getPeriodMonth() + " está cerrado y no admite cambios.");
		}
	}

	private static void verifyDueDate(YearMonth period, LocalDate dueDate) {
		if (!EntryDueDateRange.contains(period, dueDate)) {
			throw BusinessException.invalidField("dueDate", "El vencimiento debe estar entre el "
					+ DATE.format(EntryDueDateRange.earliest(period)) + " y el "
					+ DATE.format(EntryDueDateRange.latest(period)) + ".");
		}
	}

	private Account findAccount(long userId, long accountId) {
		return accounts.findByIdAndUserId(accountId, userId)
				.orElseThrow(() -> BusinessException.invalidField("accountId", "La cuenta no existe."));
	}

	private Category findCategory(long userId, long categoryId) {
		return categories.findByIdAndUserId(categoryId, userId)
				.orElseThrow(() -> BusinessException.invalidField("categoryId", "La categoría no existe."));
	}

	private static BigDecimal amount(BigDecimal value) {
		return value.setScale(2, RoundingMode.UNNECESSARY);
	}
}
