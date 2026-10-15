package com.smartcoin.entry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;

import com.smartcoin.account.domain.Account;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.ManualEntriesPolicy;
import com.smartcoin.entry.domain.QuickSettleBlockReason;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.entry.service.ConsolidationService.Prepared;
import com.smartcoin.entry.service.ConsolidationService.StateBlock;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.movement.service.MovementService;
import com.smartcoin.movement.service.MovementService.MovementRow;
import com.smartcoin.movement.service.MovementService.NewMovement;
import com.smartcoin.movement.service.MovementService.Registered;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pago rápido (HU-22, RN-24): registra un movimiento por exactamente el pendiente y consolida, todo en una sola
 * transacción. No tiene reglas propias más que «hay pendiente»: valida el movimiento con {@link MovementService}
 * (RN-21) y la consolidación con {@link ConsolidationService} (RN-25, RN-26), y reutiliza su plan.
 *
 * <p>Orden de los errores, el primero que falla (D-37): la partida (404, RN-01); el período cerrado
 * ({@code PERIOD_CLOSED}); el estado ({@code ENTRY_NOT_PENDING}); el pendiente ({@code NOTHING_PENDING}); la cuenta
 * (400 en {@code accountId}), la moneda ({@code CURRENCY_MISMATCH}) y la fecha ({@code DATE_OUT_OF_RANGE} y después
 * {@code PERIOD_CLOSED} por el mes); y, por último, las editadas del destino sin política
 * ({@code MANUAL_POLICY_REQUIRED}).
 *
 * <p>Todo se evalúa antes de guardar nada: el movimiento recién se escribe cuando la consolidación ya está
 * validada, de modo que un pedido rechazado no llega a guardar ni el movimiento. La transacción es la red de
 * seguridad. Como el movimiento todavía no existe al validar, la consolidación se evalúa con el real de hoy más el
 * pendiente; por eso {@code CONSOLIDATION_REQUIRES_MOVEMENT} no puede ocurrir.
 */
@Service
public class QuickSettleService {

	/** Lo que el usuario puede elegir. Los tres son opcionales: sin fecha, hoy; sin cuenta, la de la partida. */
	public record Request(LocalDate date, Long accountId, ManualEntriesPolicy policy) {
	}

	/**
	 * Vista previa. Con {@code blockedBy} informado no hay {@code amount} ni {@code consolidation}. Si no,
	 * {@code amount} es el pendiente que se registraría y {@code consolidation} es la vista previa de la
	 * consolidación con ese movimiento ya registrado: su {@code actualAmount} es el real de hoy más el pendiente.
	 */
	public record Preview(long entryId, QuickSettleBlockReason blockedBy, String blockedDetail, BigDecimal amount,
			ConsolidationService.Preview consolidation) {

		public boolean allowed() {
			return blockedBy == null;
		}
	}

	/** El movimiento creado y la partida ya consolidada, con lo que propagó. */
	public record Result(MovementRow movement, ConsolidationService.Result consolidation) {
	}

	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final BudgetItemRepository items;
	private final MovementService movementService;
	private final ConsolidationService consolidationService;
	private final Clock clock;

	public QuickSettleService(BudgetEntryRepository entries, MovementRepository movements, BudgetItemRepository items,
			MovementService movementService, ConsolidationService consolidationService, Clock clock) {
		this.entries = entries;
		this.movements = movements;
		this.items = items;
		this.movementService = movementService;
		this.consolidationService = consolidationService;
		this.clock = clock;
	}

	/**
	 * Qué pasaría al pagar o cobrar todo, sin modificar nada. No lleva fecha ni cuenta: la estimación y el destino no
	 * dependen de ellas, y se validan al confirmar. Inexistente o ajena: 404.
	 */
	@Transactional(readOnly = true)
	public Preview preview(long userId, long id) {
		BudgetEntry entry = find(userId, id);
		BigDecimal real = real(userId, entry);
		Block block = block(entry, real);
		if (block != null) {
			return new Preview(id, block.reason(), block.detail(), null, null);
		}
		BigDecimal pending = pending(entry, real);
		return new Preview(id, null, null, pending,
				consolidationService.previewSettling(userId, entry, real.add(pending)));
	}

	/** Registra el movimiento por el pendiente y consolida (RN-24). Todo o nada. */
	@Transactional
	public Result quickSettle(long userId, long id, Request request) {
		// Primera lectura: el bloqueo del Concepto, antes de la instantánea de la transacción (como consolidar).
		items.lockByUserIdAndEntryId(userId, id);
		BudgetEntry entry = find(userId, id);
		BigDecimal real = real(userId, entry);
		Block block = block(entry, real);
		if (block != null) {
			throw new BusinessException(block.reason().code(), block.detail());
		}
		BigDecimal pending = pending(entry, real);
		LocalDate date = request.date() == null ? LocalDate.now(clock) : request.date();
		Long accountId = request.accountId() == null ? entry.getAccount().getId() : request.accountId();

		Account account = movementService.validate(userId, entry, accountId, date);
		Prepared prepared = consolidationService.prepare(userId, entry, real.add(pending), request.policy());

		Registered registered = movementService.save(userId, entry, account,
				new NewMovement(date, pending, account.getId(), null));
		return new Result(registered.movement(), consolidationService.apply(prepared));
	}

	private record Block(QuickSettleBlockReason reason, String detail) {
	}

	/** Período cerrado, partida consolidada o nada pendiente, en ese orden; {@code null} si se puede. */
	private static Block block(BudgetEntry entry, BigDecimal real) {
		StateBlock state = ConsolidationService.stateBlock(entry);
		if (state != null) {
			return new Block(QuickSettleBlockReason.valueOf(state.reason().name()), state.detail());
		}
		if (pending(entry, real).signum() == 0) {
			return new Block(QuickSettleBlockReason.NOTHING_PENDING,
					"No hay nada pendiente en esta partida. Si ya tiene movimientos, consolidala.");
		}
		return null;
	}

	/** RN-17: lo que falta para llegar al presupuestado, nunca negativo. */
	private static BigDecimal pending(BudgetEntry entry, BigDecimal real) {
		return EntryAmounts.of(entry.getBudgetedAmount(), entry.getStatus(), entry.getConsolidatedAmount(), real)
				.pending();
	}

	private BigDecimal real(long userId, BudgetEntry entry) {
		BigDecimal total = movements.sumByEntry(userId, entry.getId());
		return (total == null ? BigDecimal.ZERO : total).setScale(2, RoundingMode.UNNECESSARY);
	}

	private BudgetEntry find(long userId, long id) {
		return entries.findByIdAndUserIdWithDetails(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));
	}
}
