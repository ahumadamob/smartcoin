package com.smartcoin.transfer.repository;

import java.time.LocalDate;

import com.smartcoin.transfer.domain.Transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

	/** HU-07 (RN-33): ¿hay transferencias del usuario que salgan de esta cuenta o entren a ella? */
	@Query("""
			select count(t) > 0 from Transfer t
			where t.userId = :userId and (t.sourceAccount.id = :accountId or t.targetAccount.id = :accountId)""")
	boolean existsByUserIdAndAccountId(@Param("userId") Long userId, @Param("accountId") Long accountId);

	/** HU-07 (RN-33): fecha de la primera transferencia de la cuenta (como origen o destino), o {@code null}. */
	@Query("""
			select min(t.transferDate) from Transfer t
			where t.userId = :userId and (t.sourceAccount.id = :accountId or t.targetAccount.id = :accountId)""")
	LocalDate findFirstTransferDate(@Param("userId") Long userId, @Param("accountId") Long accountId);
}
