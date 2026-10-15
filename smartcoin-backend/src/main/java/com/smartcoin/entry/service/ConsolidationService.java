package com.smartcoin.entry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.ConsolidationBlockReason;
import com.smartcoin.entry.domain.EstimationCalculator;
import com.smartcoin.entry.domain.EstimationCalculator.BaseEntry;
import com.smartcoin.entry.domain.EstimationCalculator.Change;
import com.smartcoin.entry.domain.EstimationCalculator.EntryFacts;
import com.smartcoin.entry.domain.EstimationCalculator.Plan;
import com.smartcoin.entry.domain.EstimationCalculator.Target;
import com.smartcoin.entry.domain.ManualEntriesPolicy;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.service.PeriodViewService;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consolidar una partida (HU-23, RN-25 a RN-28). La vista previa y la consolidación salen del mismo plan
 * ({@link EstimationCalculator}): la vista previa lo calcula suponiendo la partida ya consolidada y no guarda nada.
 *
 * <p>Orden de los errores, el primero que falla (D-36): la partida (404, RN-01); el período cerrado
 * ({@code PERIOD_CLOSED}); el estado ({@code ENTRY_NOT_PENDING}); al menos un movimiento
 * ({@code CONSOLIDATION_REQUIRES_MOVEMENT}); y, por último, la política de las editadas del destino
 * ({@code MANUAL_POLICY_REQUIRED}), que necesita el plan. La consolidación es una sola transacción: la partida, el
 * Concepto y las partidas de destino cambian juntos o no cambia nada.
 */
@Service
public class ConsolidationService {

	/**
	 * Vista previa (RN-27). Con {@code blockedBy} informado no hay plan: {@code estimate}, {@code currentAmount} y
	 * {@code estimationRule} son {@code null} y {@code targets} está vacío. Una partida sin Concepto no propaga:
	 * {@code recurring} es {@code false} y tampoco hay plan.
	 *
	 * @param actualAmount real: la suma de sus movimientos; es lo que quedaría consolidado
	 * @param currentAmount monto vigente del Concepto antes de consolidar
	 * @param estimateBasis las consolidaciones que entran en {@code estimate}, de la más reciente a la más antigua
	 */
	public record Preview(long entryId, YearMonth period, boolean recurring, ConsolidationBlockReason blockedBy,
			String blockedDetail, BigDecimal actualAmount, EstimationRule estimationRule, BigDecimal estimate,
			BigDecimal currentAmount, List<BaseEntry> estimateBasis, List<Target> targets,
			boolean manualPolicyRequired) {

		public boolean allowed() {
			return blockedBy == null;
		}
	}

	/**
	 * @param entry           la partida ya consolidada, con la forma de la vista del mes
	 * @param estimate        E, o {@code null} si la partida no tiene Concepto y no propagó
	 * @param updatedEntries  cuántas partidas de destino cambiaron
	 */
	public record Result(EntryRow entry, BigDecimal estimate, int updatedEntries) {
	}

	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final BudgetItemRepository items;
	private final Clock clock;

	public ConsolidationService(BudgetEntryRepository entries, MovementRepository movements,
			BudgetItemRepository items, Clock clock) {
		this.entries = entries;
		this.movements = movements;
		this.items = items;
		this.clock = clock;
	}

	/** Qué pasaría al consolidar, sin modificar nada (RN-27). Inexistente o ajena: 404. */
	@Transactional(readOnly = true)
	public Preview preview(long userId, long id) {
		BudgetEntry entry = find(userId, id);
		return toPreview(entry, evaluate(userId, entry, actualOf(userId, entry)));
	}

	/**
	 * Consolida la partida con su real y, si es recurrente, propaga (RN-25, RN-26): el monto vigente del Concepto
	 * pasa a E y las partidas de destino cambian según la política. La política se ignora si no hace falta (sin
	 * editadas en el destino, o partida sin Concepto).
	 */
	@Transactional
	public Result consolidate(long userId, long id, ManualEntriesPolicy policy) {
		// Primera lectura: el bloqueo del Concepto, antes de la instantánea de la transacción.
		items.lockByUserIdAndEntryId(userId, id);
		BudgetEntry entry = find(userId, id);
		return apply(prepare(userId, entry, actualOf(userId, entry), policy));
	}

	/**
	 * Una consolidación ya validada, lista para aplicar. La arma {@link #prepare} sin escribir nada, para que el pago
	 * rápido (HU-22) pueda validar el movimiento y la consolidación antes de guardar cualquiera de los dos.
	 */
	record Prepared(BudgetEntry entry, BigDecimal actual, List<BudgetEntry> concept, Plan plan,
			ManualEntriesPolicy policy) {
	}

	/** Por qué una partida no admite cambios de estado: su período o su estado (RN-25), o {@code null} si lo admite. */
	record StateBlock(ConsolidationBlockReason reason, String detail) {
	}

	static StateBlock stateBlock(BudgetEntry entry) {
		BudgetPeriod period = entry.getPeriod();
		if (period.getStatus() == PeriodStatus.CLOSED) {
			return new StateBlock(ConsolidationBlockReason.PERIOD_CLOSED,
					"El período " + period.getPeriodMonth() + " está cerrado y no admite cambios.");
		}
		if (entry.getStatus() != StoredEntryStatus.PENDING) {
			return new StateBlock(ConsolidationBlockReason.ENTRY_NOT_PENDING, "La partida ya está consolidada.");
		}
		return null;
	}

	/**
	 * Valida RN-25 con {@code actual} como el monto que quedaría consolidado, y el plan de RN-26 con la política. No
	 * modifica nada: ante cualquier error la partida, el Concepto y el destino quedan como estaban. En la
	 * consolidación común {@code actual} es la suma de los movimientos; en el pago rápido, la suma más el movimiento
	 * que todavía no existe, que por eso nunca deja la partida «sin movimientos».
	 */
	Prepared prepare(long userId, BudgetEntry entry, BigDecimal actual, ManualEntriesPolicy policy) {
		Evaluation evaluation = evaluate(userId, entry, actual);
		if (evaluation.blockedBy() != null) {
			throw new BusinessException(evaluation.blockedBy().code(), evaluation.blockedDetail());
		}
		Plan plan = evaluation.plan();
		if (plan != null && plan.manualPolicyRequired() && policy == null) {
			List<Long> edited = plan.targets().stream().filter(Target::manual).map(Target::entryId).toList();
			throw new BusinessException(ErrorCode.MANUAL_POLICY_REQUIRED, "Hay " + edited.size()
					+ (edited.size() == 1 ? " partida editada" : " partidas editadas")
					+ " en los meses siguientes: elegí si respetarlas o pisarlas. No se consolidó nada.", edited);
		}
		return new Prepared(entry, actual, evaluation.concept(), plan, policy);
	}

	/** Aplica una consolidación ya validada: la partida, el Concepto y las partidas de destino. */
	Result apply(Prepared prepared) {
		BudgetEntry entry = prepared.entry();
		entry.setConsolidatedAmount(prepared.actual());
		entry.setStatus(StoredEntryStatus.CONSOLIDATED);
		entry.setConsolidatedAt(clock.instant());

		BigDecimal estimate = null;
		int updated = 0;
		Plan plan = prepared.plan();
		if (plan != null) {
			estimate = plan.estimate();
			BudgetItem item = entry.getBudgetItem();
			item.setCurrentAmount(estimate);
			Map<Long, BudgetEntry> byId = new HashMap<>();
			prepared.concept().forEach(candidate -> byId.put(candidate.getId(), candidate));
			for (Change change : plan.changes(prepared.policy())) {
				BudgetEntry target = byId.get(change.entryId());
				target.setBudgetedAmount(change.budgeted());
				target.setManual(change.manual());
				updated++;
			}
		}
		return new Result(PeriodViewService.row(entry, prepared.actual(), LocalDate.now(clock)), estimate, updated);
	}

	/**
	 * Qué pasaría al consolidar la partida con {@code actual} como monto consolidado, sin modificar nada. Es la vista
	 * previa del pago rápido (HU-22): el real que quedaría es el de hoy más el pendiente.
	 */
	Preview previewSettling(long userId, BudgetEntry entry, BigDecimal actual) {
		return toPreview(entry, evaluate(userId, entry, actual));
	}

	private BudgetEntry find(long userId, long id) {
		return entries.findByIdAndUserIdWithDetails(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));
	}

	/**
	 * @param concept las partidas del Concepto, o vacía si la partida no tiene
	 * @param plan    el plan de propagación, o {@code null} si no hay (partida sin Concepto o bloqueada)
	 */
	private record Evaluation(ConsolidationBlockReason blockedBy, String blockedDetail, BigDecimal actual,
			List<BudgetEntry> concept, Plan plan) {
	}

	/** La suma de los movimientos de la partida, con 2 decimales. */
	private BigDecimal actualOf(long userId, BudgetEntry entry) {
		BigDecimal total = movements.sumByEntry(userId, entry.getId());
		return (total == null ? BigDecimal.ZERO : total).setScale(2, RoundingMode.UNNECESSARY);
	}

	/** Las condiciones de RN-25 y, si se cumplen y es recurrente, el plan de RN-26. No modifica nada. */
	private Evaluation evaluate(long userId, BudgetEntry entry, BigDecimal actual) {
		BudgetPeriod period = entry.getPeriod();
		StateBlock state = stateBlock(entry);
		if (state != null) {
			return blocked(state.reason(), actual, state.detail());
		}
		if (actual.signum() == 0) {
			return blocked(ConsolidationBlockReason.CONSOLIDATION_REQUIRES_MOVEMENT, actual,
					"La partida no tiene movimientos: registrá al menos un cobro o pago antes de consolidarla.");
		}
		BudgetItem item = entry.getBudgetItem();
		if (item == null) {
			return new Evaluation(null, null, actual, List.of(), null);
		}
		List<BudgetEntry> concept = entries.findByUserIdAndBudgetItemId(userId, item.getId());
		List<EntryFacts> facts = concept.stream().map(candidate -> candidate.getId().equals(entry.getId())
				? new EntryFacts(candidate.getId(), period.getPeriodMonth(), true, true, actual, null,
						candidate.isManual(), candidate.getBudgetedAmount())
				: facts(candidate)).toList();
		return new Evaluation(null, null, actual, concept, EstimationCalculator.plan(item.getEstimationRule(), facts));
	}

	private static Evaluation blocked(ConsolidationBlockReason reason, BigDecimal actual, String detail) {
		return new Evaluation(reason, detail, actual, List.of(), null);
	}

	private static EntryFacts facts(BudgetEntry entry) {
		return new EntryFacts(entry.getId(), entry.getPeriod().getPeriodMonth(),
				entry.getPeriod().getStatus() == PeriodStatus.OPEN,
				entry.getStatus() == StoredEntryStatus.CONSOLIDATED, entry.getConsolidatedAmount(),
				entry.getClosingResolution(), entry.isManual(), entry.getBudgetedAmount());
	}

	private static Preview toPreview(BudgetEntry entry, Evaluation evaluation) {
		YearMonth period = entry.getPeriod().getPeriodMonth();
		boolean recurring = entry.getBudgetItem() != null;
		Plan plan = evaluation.plan();
		if (plan == null) {
			return new Preview(entry.getId(), period, recurring, evaluation.blockedBy(), evaluation.blockedDetail(),
					evaluation.actual(), null, null, null, List.of(), List.of(), false);
		}
		BudgetItem item = entry.getBudgetItem();
		return new Preview(entry.getId(), period, true, null, null, evaluation.actual(), item.getEstimationRule(),
				plan.estimate(), item.getCurrentAmount(), plan.basis(), plan.targets(), plan.manualPolicyRequired());
	}
}
