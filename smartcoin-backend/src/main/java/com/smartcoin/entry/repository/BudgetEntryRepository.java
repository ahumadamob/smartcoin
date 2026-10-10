package com.smartcoin.entry.repository;

import java.util.List;
import java.util.Optional;

import com.smartcoin.entry.domain.BudgetEntry;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
