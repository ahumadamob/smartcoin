package com.smartcoin.period.repository;

import com.smartcoin.period.domain.AccountClosing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountClosingRepository extends JpaRepository<AccountClosing, Long> {

	/** HU-07 (RN-33): ¿la cuenta tiene cierres? */
	@Query("select count(c) > 0 from AccountClosing c where c.userId = :userId and c.account.id = :accountId")
	boolean existsByUserIdAndAccountId(@Param("userId") Long userId, @Param("accountId") Long accountId);
}
