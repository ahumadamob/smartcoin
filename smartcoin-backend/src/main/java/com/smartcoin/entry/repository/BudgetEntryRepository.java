package com.smartcoin.entry.repository;

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
}
