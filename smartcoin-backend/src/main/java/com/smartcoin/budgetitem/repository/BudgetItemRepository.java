package com.smartcoin.budgetitem.repository;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import com.smartcoin.budgetitem.domain.BudgetItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, Long> {

	/** HU-13 (RN-01): un Concepto del usuario; vacío si no existe o es de otro usuario. */
	Optional<BudgetItem> findByIdAndUserId(Long id, Long userId);

	/**
	 * HU-14: todos los Conceptos del usuario con su cuenta por defecto y su categoría en la misma consulta, para
	 * que la lista no haga una consulta por fila. Sin orden: lo decide el servicio (D-28).
	 */
	@Query("""
			select i from BudgetItem i
			join fetch i.defaultAccount
			left join fetch i.category
			where i.userId = :userId""")
	List<BudgetItem> findAllByUserIdWithAccountAndCategory(@Param("userId") Long userId);

	/** HU-07 (RN-33): ¿algún Concepto del usuario tiene esta cuenta por defecto? */
	@Query("select count(i) > 0 from BudgetItem i where i.userId = :userId and i.defaultAccount.id = :accountId")
	boolean existsByUserIdAndAccountId(@Param("userId") Long userId, @Param("accountId") Long accountId);

	/** HU-09 (RN-34): ¿algún Concepto del usuario usa esta categoría? */
	@Query("select count(i) > 0 from BudgetItem i where i.userId = :userId and i.category.id = :categoryId")
	boolean existsByUserIdAndCategoryId(@Param("userId") Long userId, @Param("categoryId") Long categoryId);

	/**
	 * HU-12 (RN-07, RN-13): los Conceptos del usuario a los que todavía les falta generar, es decir, los que tienen
	 * {@code generated_until} anterior al menor entre el horizonte y su fin. Uno terminado o al día no se trae.
	 */
	@Query("""
			select i from BudgetItem i
			where i.userId = :userId
			  and (i.generatedUntil is null
			       or (i.generatedUntil < :horizon and (i.endPeriod is null or i.generatedUntil < i.endPeriod)))
			order by i.id""")
	List<BudgetItem> findPendingGeneration(@Param("userId") Long userId, @Param("horizon") YearMonth horizon);

	/**
	 * HU-18 (RN-31): elimina el Concepto del usuario. Solo después de eliminar sus partidas: la clave foránea de
	 * {@code budget_entry.budget_item_id} es {@code RESTRICT}.
	 *
	 * @return cuántas filas se eliminaron
	 */
	@Modifying
	@Query("delete from BudgetItem i where i.userId = :userId and i.id = :id")
	int deleteByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);
}
