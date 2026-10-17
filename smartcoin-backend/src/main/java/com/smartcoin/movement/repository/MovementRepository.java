package com.smartcoin.movement.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.AccountTotal;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.movement.domain.Movement;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovementRepository extends JpaRepository<Movement, Long> {

	/** HU-07 (RN-33): ¿hay movimientos del usuario en esta cuenta? */
	@Query("select count(m) > 0 from Movement m where m.userId = :userId and m.account.id = :accountId")
	boolean existsByUserIdAndAccountId(@Param("userId") Long userId, @Param("accountId") Long accountId);

	/** HU-07 (RN-33): fecha del primer movimiento de la cuenta, o {@code null} si no tiene. */
	@Query("select min(m.movementDate) from Movement m where m.userId = :userId and m.account.id = :accountId")
	LocalDate findFirstMovementDate(@Param("userId") Long userId, @Param("accountId") Long accountId);

	/**
	 * HU-08 (RN-35): suma de los movimientos del usuario con fecha menor o igual a {@code date}, agrupada por la cuenta
	 * del movimiento. El signo lo define el tipo de la partida: se consulta una vez por {@code kind}. Cuenta la fecha
	 * del movimiento, no el período de su partida. Las cuentas sin movimientos no figuran.
	 */
	@Query("""
			select new com.smartcoin.account.domain.AccountTotal(m.account.id, sum(m.amount))
			from Movement m
			where m.userId = :userId and m.movementDate <= :date and m.entry.kind = :kind
			group by m.account.id""")
	List<AccountTotal> sumByAccountUpTo(@Param("userId") Long userId, @Param("date") LocalDate date,
			@Param("kind") EntryKind kind);

	/** HU-13 (RN-15): de estas partidas del usuario, los ids de las que tienen al menos un movimiento. */
	@Query("select distinct m.entry.id from Movement m where m.userId = :userId and m.entry.id in :entryIds")
	List<Long> findEntryIdsWithMovements(@Param("userId") Long userId, @Param("entryIds") Collection<Long> entryIds);

	/**
	 * HU-15 (RN-17): suma de los movimientos de cada partida de un período del usuario, en una sola consulta. Cuenta
	 * el período de la partida, no la fecha del movimiento. Las partidas sin movimientos no figuran.
	 */
	@Query("""
			select new com.smartcoin.entry.domain.EntryTotal(m.entry.id, sum(m.amount))
			from Movement m
			where m.userId = :userId and m.entry.period.id = :periodId
			group by m.entry.id""")
	List<EntryTotal> sumByEntryOfPeriod(@Param("userId") Long userId, @Param("periodId") Long periodId);

	/**
	 * HU-36 (RN-17): la suma de los movimientos de cada partida de los períodos del usuario entre dos meses, inclusive,
	 * en una sola consulta. Es {@link #sumByEntryOfPeriod} para un rango.
	 */
	@Query("""
			select new com.smartcoin.entry.domain.EntryTotal(m.entry.id, sum(m.amount))
			from Movement m
			where m.userId = :userId and m.entry.period.periodMonth between :from and :to
			group by m.entry.id""")
	List<EntryTotal> sumByEntryOfPeriodRange(@Param("userId") Long userId, @Param("from") YearMonth from,
			@Param("to") YearMonth to);

	/**
	 * HU-16 (RN-17): suma de los movimientos de una partida del usuario, o {@code null} si no tiene ninguno. Es la
	 * misma suma que {@link #sumByEntryOfPeriod}, para una sola partida.
	 */
	@Query("select sum(m.amount) from Movement m where m.userId = :userId and m.entry.id = :entryId")
	BigDecimal sumByEntry(@Param("userId") Long userId, @Param("entryId") Long entryId);

	/**
	 * HU-19: los movimientos de una partida del usuario, por fecha y, a igual fecha, por orden de creación, con su
	 * cuenta en la misma consulta.
	 */
	@Query("""
			select m from Movement m
			join fetch m.account
			where m.userId = :userId and m.entry.id = :entryId
			order by m.movementDate, m.id""")
	List<Movement> findByUserIdAndEntryIdWithAccount(@Param("userId") Long userId, @Param("entryId") Long entryId);

	/**
	 * HU-21: un movimiento del usuario con su cuenta; {@code null} si no existe o es de otro usuario (RN-01). La
	 * partida se carga aparte, con su período, para devolverla ya con sus valores derivados.
	 */
	@Query("""
			select m from Movement m
			join fetch m.account
			where m.id = :id and m.userId = :userId""")
	Optional<Movement> findByIdAndUserIdWithAccount(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * HU-34 (RN-45): los movimientos del usuario con fecha entre {@code from} y {@code to} (inclusive) en cuentas de la
	 * moneda, con su cuenta, su partida y el Concepto de la partida en la misma consulta. Cuenta la cuenta del
	 * movimiento, no la de la partida, y su fecha, no el período de su partida (RN-41). Sin orden: lo decide la regla.
	 */
	@Query("""
			select m from Movement m
			join fetch m.account a
			join fetch m.entry e
			left join fetch e.budgetItem
			where m.userId = :userId and a.currency = :currency
				and m.movementDate >= :from and m.movementDate <= :to""")
	List<Movement> findForCashFlow(@Param("userId") Long userId, @Param("currency") Currency currency,
			@Param("from") LocalDate from, @Param("to") LocalDate to);

	/**
	 * HU-34 (RN-17): suma de los movimientos de cada partida pendiente del usuario cuya cuenta es de la moneda y cuyo
	 * vencimiento es menor o igual a {@code dueUntil}, en una sola consulta: es la misma selección de partidas que
	 * {@code BudgetEntryRepository.findPendingForCashFlow}. Las partidas sin movimientos no figuran.
	 */
	@Query("""
			select new com.smartcoin.entry.domain.EntryTotal(e.id, sum(m.amount))
			from Movement m join m.entry e
			where m.userId = :userId and e.status = :status and e.account.currency = :currency
				and e.dueDate <= :dueUntil
			group by e.id""")
	List<EntryTotal> sumByPendingEntriesDueUntil(@Param("userId") Long userId, @Param("status") StoredEntryStatus status,
			@Param("currency") Currency currency, @Param("dueUntil") LocalDate dueUntil);

	/**
	 * HU-35 (RN-17): como {@link #sumByPendingEntriesDueUntil}, pero de todas las monedas; es la misma selección de
	 * partidas que {@code BudgetEntryRepository.findPendingUntil}.
	 */
	@Query("""
			select new com.smartcoin.entry.domain.EntryTotal(e.id, sum(m.amount))
			from Movement m join m.entry e
			where m.userId = :userId and e.status = :status and e.dueDate <= :dueUntil
			group by e.id""")
	List<EntryTotal> sumByPendingEntriesUntil(@Param("userId") Long userId, @Param("status") StoredEntryStatus status,
			@Param("dueUntil") LocalDate dueUntil);
}
