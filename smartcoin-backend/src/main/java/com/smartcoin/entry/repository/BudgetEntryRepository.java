package com.smartcoin.entry.repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.shared.domain.Currency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetEntryRepository extends JpaRepository<BudgetEntry, Long> {

	/** HU-07 (RN-33): ¿alguna partida del usuario tiene esta cuenta prevista? */
	@Query("select count(e) > 0 from BudgetEntry e where e.userId = :userId and e.account.id = :accountId")
	boolean existsByUserIdAndAccountId(@Param("userId") Long userId, @Param("accountId") Long accountId);

	/** HU-09 (RN-34): ¿alguna partida del usuario usa esta categoría? */
	@Query("select count(e) > 0 from BudgetEntry e where e.userId = :userId and e.category.id = :categoryId")
	boolean existsByUserIdAndCategoryId(@Param("userId") Long userId, @Param("categoryId") Long categoryId);

	/**
	 * HU-13 (RN-15): todas las partidas de un Concepto, de cualquier estado y período, con su período, en orden. El
	 * servicio decide cuáles puede tocar un cambio del Concepto: acá no se filtra, así el filtro se prueba sin base.
	 */
	@Query("""
			select e from BudgetEntry e join fetch e.period p
			where e.userId = :userId and e.budgetItem.id = :budgetItemId
			order by p.periodMonth""")
	List<BudgetEntry> findByUserIdAndBudgetItemId(@Param("userId") Long userId,
			@Param("budgetItemId") Long budgetItemId);

	/**
	 * HU-15 (RN-44): las partidas de un período del usuario con su cuenta, su categoría, su Concepto y la categoría
	 * del Concepto en la misma consulta, para que la vista del mes no haga una consulta por fila. Sin orden: lo
	 * decide el servicio.
	 */
	@Query("""
			select e from BudgetEntry e
			join fetch e.account
			left join fetch e.category
			left join fetch e.budgetItem i
			left join fetch i.category
			where e.userId = :userId and e.period.id = :periodId""")
	List<BudgetEntry> findByUserIdAndPeriodIdWithDetails(@Param("userId") Long userId,
			@Param("periodId") Long periodId);

	/**
	 * HU-36 (RN-44): las partidas de todos los períodos del usuario entre dos meses, inclusive, con cuenta, categoría,
	 * Concepto, categoría y cuenta por defecto del Concepto, en una sola consulta. Es la de la vista del mes, para un
	 * rango.
	 */
	@Query("""
			select e from BudgetEntry e
			join fetch e.period p
			join fetch e.account
			left join fetch e.category
			left join fetch e.budgetItem i
			left join fetch i.category
			left join fetch i.defaultAccount
			where e.userId = :userId and p.periodMonth between :from and :to""")
	List<BudgetEntry> findByUserIdAndPeriodRangeWithDetails(@Param("userId") Long userId,
			@Param("from") YearMonth from, @Param("to") YearMonth to);

	/**
	 * HU-16 (RN-18): una partida del usuario con su período, cuenta, categoría, Concepto y categoría del Concepto en la
	 * misma consulta, para armar la respuesta sin consultas por asociación. Vacía si no existe o es de otro usuario.
	 */
	@Query("""
			select e from BudgetEntry e
			join fetch e.period
			join fetch e.account
			left join fetch e.category
			left join fetch e.budgetItem i
			left join fetch i.category
			where e.id = :id and e.userId = :userId""")
	Optional<BudgetEntry> findByIdAndUserIdWithDetails(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * HU-18 (RN-30, RN-31): elimina de una sola vez las partidas del usuario con estos ids, en una sola sentencia.
	 * Las claves foráneas son {@code RESTRICT} y no hay cascadas: el servicio ya verificó que ninguna tiene
	 * movimientos ni está consolidada, y elimina el Concepto después, no antes.
	 *
	 * @return cuántas filas se eliminaron
	 */
	@Modifying
	@Query("delete from BudgetEntry e where e.userId = :userId and e.id in :ids")
	int deleteByUserIdAndIdIn(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

	/**
	 * HU-34 (RN-45): las partidas del usuario con este estado, cuya cuenta es de la moneda y cuyo vencimiento es menor o
	 * igual a {@code dueUntil}, con su cuenta y su Concepto en la misma consulta. Con {@code PENDING}, las candidatas
	 * a filas previstas del flujo de caja y al saldo de arranque proyectado: el servicio decide, con la fecha efectiva,
	 * cuáles entran. Sin orden: lo decide la regla.
	 */
	@Query("""
			select e from BudgetEntry e
			join fetch e.account a
			left join fetch e.budgetItem
			where e.userId = :userId and e.status = :status and a.currency = :currency and e.dueDate <= :dueUntil""")
	List<BudgetEntry> findPendingForCashFlow(@Param("userId") Long userId, @Param("status") StoredEntryStatus status,
			@Param("currency") Currency currency, @Param("dueUntil") LocalDate dueUntil);

	/**
	 * HU-35 (RN-46): como {@link #findPendingForCashFlow}, pero de todas las monedas, para proyectar las cuentas de una
	 * vez. Con su cuenta y su Concepto en la misma consulta.
	 */
	@Query("""
			select e from BudgetEntry e
			join fetch e.account
			where e.userId = :userId and e.status = :status and e.dueDate <= :dueUntil""")
	List<BudgetEntry> findPendingUntil(@Param("userId") Long userId, @Param("status") StoredEntryStatus status,
			@Param("dueUntil") LocalDate dueUntil);
}
