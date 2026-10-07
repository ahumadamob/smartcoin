package com.smartcoin.budgetitem.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.BudgetItemEditEffects;
import com.smartcoin.budgetitem.domain.BudgetItemEditEffects.EditedField;
import com.smartcoin.budgetitem.domain.BudgetItemEditEffects.EntryState;
import com.smartcoin.budgetitem.domain.BudgetItemEditability;
import com.smartcoin.budgetitem.domain.BudgetItemStatus;
import com.smartcoin.budgetitem.domain.BudgetItemStatusCalculator;
import com.smartcoin.budgetitem.domain.BudgetItemValues;
import com.smartcoin.budgetitem.domain.DueDateCalculator;
import com.smartcoin.budgetitem.domain.InstallmentPlan;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodRange;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Conceptos (HU-10, HU-13, RN-10, RN-15). Toda consulta lleva el {@code userId} del usuario actual. */
@Service
public class BudgetItemService {

	/** El Concepto creado y las partidas que generó, en orden de período. */
	public record Created(BudgetItem item, List<BudgetEntry> entries) {
	}

	/**
	 * Cuántas partidas del Concepto, pendientes y de períodos abiertos, puede tocar un cambio de su monto vigente
	 * ({@code pendingNotManual}) y cuántas son editadas y por eso no cambian ({@code pendingManual}). RN-15.
	 */
	public record EntryCounts(int pendingNotManual, int pendingManual) {
	}

	/**
	 * Un Concepto con el efecto que tendría sobre sus partidas un cambio de monto vigente. La moneda se lee acá,
	 * dentro de la transacción: la cuenta por defecto se carga diferida y fuera de ella no se puede leer.
	 */
	public record Detail(BudgetItem item, Currency currency, EntryCounts counts) {
	}

	/**
	 * Una fila de la lista. La cuenta, la moneda y la categoría se leen acá, dentro de la transacción, para que la
	 * respuesta no lea asociaciones fuera de ella ({@code open-in-view: false}).
	 */
	public record ListRow(BudgetItem item, Long accountId, String accountName, Currency currency, Long categoryId,
			String categoryName, BudgetItemStatusCalculator.Result state) {
	}

	private final BudgetItemRepository items;
	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final AccountRepository accounts;
	private final CategoryRepository categories;
	private final BudgetPeriodRepository periods;
	private final UserRepository users;
	private final HorizonService horizon;
	private final EntryGenerator generator;
	private final AppProperties properties;
	private final Clock clock;

	public BudgetItemService(BudgetItemRepository items, BudgetEntryRepository entries, MovementRepository movements,
			AccountRepository accounts, CategoryRepository categories, BudgetPeriodRepository periods,
			UserRepository users, HorizonService horizon, EntryGenerator generator, AppProperties properties,
			Clock clock) {
		this.items = items;
		this.entries = entries;
		this.movements = movements;
		this.accounts = accounts;
		this.categories = categories;
		this.periods = periods;
		this.users = users;
		this.horizon = horizon;
		this.generator = generator;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Crea un Concepto, recurrente o en cuotas, y genera sus partidas (RN-13) en la misma transacción. Primero lo que
	 * está mal en el pedido (400); después lo que el estado de los períodos no permite (409). En un plan de cuotas el
	 * período de fin no se informa: se calcula (RN-14).
	 */
	@Transactional
	public Created create(long userId, BudgetItemValues values) {
		User user = users.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Credenciales inválidas o ausentes."));
		InstallmentPlan plan = installmentPlan(values);
		if (values.endPeriod() != null && values.endPeriod().isBefore(values.startPeriod())) {
			throw BusinessException.invalidField("endPeriod",
					"El período de fin no puede ser anterior al período de inicio.");
		}
		// Van en el cuerpo, no en la ruta: una cuenta o categoría inexistente o ajena es un dato inválido (RN-10).
		Account account = accounts.findByIdAndUserId(values.defaultAccountId(), userId)
				.orElseThrow(() -> BusinessException.invalidField("defaultAccountId", "La cuenta por defecto no existe."));
		Category category = values.categoryId() == null ? null
				: categories.findByIdAndUserId(values.categoryId(), userId)
						.orElseThrow(() -> BusinessException.invalidField("categoryId", "La categoría no existe."));

		YearMonth last = PeriodRange.horizon(YearMonth.now(clock), properties.budget().horizonMonths());
		YearMonth firstOpen = PeriodRange.firstOpen(user.getStartPeriod(),
				periods.findFirstByUserIdAndStatusOrderByPeriodMonthDesc(userId, PeriodStatus.CLOSED)
						.map(BudgetPeriod::getPeriodMonth).orElse(null));
		if (values.startPeriod().isBefore(firstOpen) || values.startPeriod().isAfter(last)) {
			throw new BusinessException(ErrorCode.PERIOD_NOT_AVAILABLE, "El período de inicio debe estar entre "
					+ firstOpen + " (el primer período abierto) y " + last + " (el horizonte).");
		}

		// RN-07: si el mes cambió desde el último inicio de sesión, puede faltar el período del horizonte.
		horizon.ensureHorizon(user);

		BudgetItem item = new BudgetItem();
		item.setUserId(userId);
		item.setName(values.name().strip());
		item.setKind(values.kind());
		item.setDefaultAccount(account);
		item.setCategory(category);
		item.setPeriodicity(values.periodicity());
		item.setDueDay(values.dueDay());
		item.setDueMonthOffset(values.dueMonthOffset());
		item.setStartPeriod(values.startPeriod());
		if (plan == null) {
			item.setEndPeriod(values.endPeriod());
		}
		else {
			item.setEndPeriod(plan.endPeriod(values.startPeriod(), values.periodicity()));
			item.setInstallmentsTotal(plan.total());
			item.setFirstInstallmentNumber(plan.first());
		}
		item.setEstimationRule(values.estimationRule());
		item.setCurrentAmount(values.currentAmount().setScale(2, RoundingMode.UNNECESSARY));
		BudgetItem saved = items.save(item);
		return new Created(saved, generator.generate(saved, last));
	}

	@Transactional(readOnly = true)
	public Detail get(long userId, long id) {
		return detail(userId, find(userId, id));
	}

	/**
	 * Lista los Conceptos del usuario (HU-14, D-28), con el estado calculado contra el período actual. Los filtros
	 * se aplican acá sobre los Conceptos del usuario. Una categoría inexistente o ajena en el filtro es 404.
	 * Orden: los Finalizados al final y, dentro de cada grupo, por nombre sin distinguir mayúsculas.
	 */
	@Transactional(readOnly = true)
	public List<ListRow> list(long userId, EntryKind kind, Long categoryId, boolean withoutCategory) {
		if (categoryId != null && withoutCategory) {
			throw BusinessException.invalidField("withoutCategory",
					"No se puede filtrar por una categoría y por «sin categoría» a la vez.");
		}
		if (categoryId != null) {
			categories.findByIdAndUserId(categoryId, userId)
					.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La categoría no existe."));
		}
		YearMonth current = YearMonth.now(clock);
		return items.findAllByUserIdWithAccountAndCategory(userId).stream()
				.filter(i -> kind == null || i.getKind() == kind)
				.filter(i -> categoryId == null
						|| (i.getCategory() != null && categoryId.equals(i.getCategory().getId())))
				.filter(i -> !withoutCategory || i.getCategory() == null)
				.map(i -> {
					Account account = i.getDefaultAccount();
					Category category = i.getCategory();
					return new ListRow(i, account.getId(), account.getName(), account.getCurrency(),
							category == null ? null : category.getId(), category == null ? null : category.getName(),
							BudgetItemStatusCalculator.calculate(i.getStartPeriod(), i.getEndPeriod(),
									i.getPeriodicity(), i.installmentPlan(), current));
				})
				.sorted(Comparator
						.comparing((ListRow r) -> r.state().status() == BudgetItemStatus.FINISHED)
						.thenComparing(r -> r.item().getName(), String.CASE_INSENSITIVE_ORDER)
						.thenComparing(r -> r.item().getId()))
				.toList();
	}

	/**
	 * Edita un Concepto (RN-15) y propaga el cambio a sus partidas, todo en la misma transacción. Primero lo que está
	 * mal en el pedido (400: cuenta o categoría que no existen, D-24); después lo que el Concepto no permite (409: un
	 * dato no editable, una cuenta de otra moneda). Con un pedido rechazado no cambia nada.
	 *
	 * <p>El Concepto se modifica antes de asegurar el horizonte (RN-07): las partidas que esa operación genera salen
	 * ya con los datos nuevos. Las existentes se recalculan antes, así no se tocan dos veces. Las consolidadas y las de
	 * períodos cerrados nunca se tocan (RN-09).
	 */
	@Transactional
	public Detail update(long userId, long id, BudgetItemValues values) {
		BudgetItem item = find(userId, id);
		BudgetItemValues current = BudgetItemValues.of(item);

		// Van en el cuerpo, no en la ruta: una cuenta o categoría inexistente o ajena es un dato inválido (D-24).
		Account account = accounts.findByIdAndUserId(values.defaultAccountId(), userId)
				.orElseThrow(() -> BusinessException.invalidField("defaultAccountId", "La cuenta por defecto no existe."));
		Category category = values.categoryId() == null ? null
				: categories.findByIdAndUserId(values.categoryId(), userId)
						.orElseThrow(() -> BusinessException.invalidField("categoryId", "La categoría no existe."));

		BudgetItemEditability.verifyChange(current, values);
		Currency currency = item.getDefaultAccount().getCurrency();
		if (account.getCurrency() != currency) {
			throw new BusinessException(ErrorCode.CURRENCY_MISMATCH, "La cuenta por defecto tiene que ser de la misma "
					+ "moneda que la actual (" + currency + "): las partidas no cambian de moneda.");
		}

		BigDecimal amount = values.currentAmount().setScale(2, RoundingMode.UNNECESSARY);
		boolean dueDateChanged = current.dueDay() != values.dueDay()
				|| current.dueMonthOffset() != values.dueMonthOffset();
		boolean accountChanged = !current.defaultAccountId().equals(account.getId());
		boolean amountChanged = current.currentAmount().compareTo(amount) != 0;

		item.setName(values.name().strip());
		item.setCategory(category);
		item.setEstimationRule(values.estimationRule());
		item.setDueDay(values.dueDay());
		item.setDueMonthOffset(values.dueMonthOffset());
		item.setDefaultAccount(account);
		item.setCurrentAmount(amount);

		if (dueDateChanged || accountChanged || amountChanged) {
			propagate(userId, item, dueDateChanged, accountChanged, amountChanged);
		}

		// RN-07: también al editar. Es idempotente; si el horizonte avanzó, genera con los datos que acaban de cambiar.
		User user = users.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Credenciales inválidas o ausentes."));
		horizon.ensureHorizon(user);
		return detail(userId, item);
	}

	/** Aplica a las partidas del Concepto los cambios que le alcanzan según RN-15. Nunca toca otras. */
	private void propagate(long userId, BudgetItem item, boolean dueDateChanged, boolean accountChanged,
			boolean amountChanged) {
		List<BudgetEntry> all = entries.findByUserIdAndBudgetItemId(userId, item.getId());
		Set<Long> withMovements = accountChanged ? entryIdsWithMovements(userId, all) : Set.of();
		for (BudgetEntry entry : all) {
			EntryState state = stateOf(entry);
			if (dueDateChanged && BudgetItemEditEffects.affects(EditedField.DUE_DATE, state, false)) {
				entry.setDueDate(DueDateCalculator.dueDate(entry.getPeriod().getPeriodMonth(), item.getDueDay(),
						item.getDueMonthOffset()));
			}
			if (accountChanged && BudgetItemEditEffects.affects(EditedField.ACCOUNT, state,
					withMovements.contains(entry.getId()))) {
				entry.setAccount(item.getDefaultAccount());
			}
			if (amountChanged && BudgetItemEditEffects.affects(EditedField.CURRENT_AMOUNT, state, false)) {
				entry.setBudgetedAmount(item.getCurrentAmount());
			}
		}
	}

	/** Ids de las partidas pendientes de períodos abiertos que tienen movimientos: las únicas que importan a la cuenta. */
	private Set<Long> entryIdsWithMovements(long userId, List<BudgetEntry> all) {
		List<Long> candidates = all.stream().filter(e -> BudgetItemEditEffects.isOpenPending(stateOf(e)))
				.map(BudgetEntry::getId).toList();
		return candidates.isEmpty() ? Set.of()
				: movements.findEntryIdsWithMovements(userId, candidates).stream().collect(Collectors.toSet());
	}

	private Detail detail(long userId, BudgetItem item) {
		List<BudgetEntry> all = entries.findByUserIdAndBudgetItemId(userId, item.getId());
		int notManual = 0;
		int manual = 0;
		for (BudgetEntry entry : all) {
			EntryState state = stateOf(entry);
			if (BudgetItemEditEffects.isOpenPending(state)) {
				if (state.manual()) {
					manual++;
				}
				else {
					notManual++;
				}
			}
		}
		return new Detail(item, item.getDefaultAccount().getCurrency(), new EntryCounts(notManual, manual));
	}

	private static EntryState stateOf(BudgetEntry entry) {
		return new EntryState(entry.getPeriod().getStatus() == PeriodStatus.OPEN, entry.getStatus(), entry.isManual());
	}

	private BudgetItem find(long userId, long id) {
		return items.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "El Concepto no existe."));
	}

	/**
	 * El plan de cuotas del pedido (RN-14), o {@code null} si no es en cuotas. La primera cuota es 1 si no se
	 * informa. El formato de cada número (≥ 1, máximo) ya lo controló Bean Validation; acá, lo que depende de otros
	 * campos.
	 */
	private static InstallmentPlan installmentPlan(BudgetItemValues values) {
		Integer total = values.installmentsTotal();
		Integer first = values.firstInstallmentNumber();
		if (total == null) {
			if (first != null) {
				throw BusinessException.invalidField("installmentsTotal",
						"El total de cuotas es obligatorio si se informa la primera cuota.");
			}
			return null;
		}
		if (first != null && first > total) {
			throw BusinessException.invalidField("firstInstallmentNumber",
					"La primera cuota no puede ser mayor que el total de cuotas.");
		}
		if (values.endPeriod() != null) {
			throw BusinessException.invalidField("endPeriod",
					"En un Concepto en cuotas el período de fin se calcula: no se informa.");
		}
		return new InstallmentPlan(total, first == null ? 1 : first);
	}
}
