package com.smartcoin.budgetitem.repository;

import com.smartcoin.budgetitem.domain.BudgetItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, Long> {

	/** HU-07 (RN-33): ¿algún Concepto del usuario tiene esta cuenta por defecto? */
	@Query("select count(i) > 0 from BudgetItem i where i.userId = :userId and i.defaultAccount.id = :accountId")
	boolean existsByUserIdAndAccountId(@Param("userId") Long userId, @Param("accountId") Long accountId);

	/** HU-09 (RN-34): ¿algún Concepto del usuario usa esta categoría? */
	@Query("select count(i) > 0 from BudgetItem i where i.userId = :userId and i.category.id = :categoryId")
	boolean existsByUserIdAndCategoryId(@Param("userId") Long userId, @Param("categoryId") Long categoryId);
}
