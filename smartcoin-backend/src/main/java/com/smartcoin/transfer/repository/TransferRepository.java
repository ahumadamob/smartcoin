package com.smartcoin.transfer.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.AccountTotal;
import com.smartcoin.transfer.domain.Transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

	/** HU-29 (RN-01): una transferencia del usuario; vacío si no existe o es de otro. */
	Optional<Transfer> findByIdAndUserId(Long id, Long userId);

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

	/** HU-08 (RN-35): monto de destino de las transferencias entrantes con fecha menor o igual a {@code date}, por cuenta. */
	@Query("""
			select new com.smartcoin.account.domain.AccountTotal(t.targetAccount.id, sum(t.targetAmount))
			from Transfer t
			where t.userId = :userId and t.transferDate <= :date
			group by t.targetAccount.id""")
	List<AccountTotal> sumIncomingByAccountUpTo(@Param("userId") Long userId, @Param("date") LocalDate date);

	/** HU-08 (RN-35): monto de origen de las transferencias salientes con fecha menor o igual a {@code date}, por cuenta. */
	@Query("""
			select new com.smartcoin.account.domain.AccountTotal(t.sourceAccount.id, sum(t.sourceAmount))
			from Transfer t
			where t.userId = :userId and t.transferDate <= :date
			group by t.sourceAccount.id""")
	List<AccountTotal> sumOutgoingByAccountUpTo(@Param("userId") Long userId, @Param("date") LocalDate date);

	/** HU-27: transferencias del usuario con fecha entre {@code from} y {@code to} (inclusive), más recientes primero. */
	@Query("""
			select t from Transfer t
			join fetch t.sourceAccount
			join fetch t.targetAccount
			where t.userId = :userId and t.transferDate >= :from and t.transferDate <= :to
			order by t.transferDate desc, t.id desc""")
	List<Transfer> findByUserIdAndDateBetween(@Param("userId") Long userId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);

	/**
	 * HU-34 (RN-45): transferencias del usuario con fecha entre {@code from} y {@code to} (inclusive) entre cuentas de
	 * monedas distintas, con sus dos cuentas. Las de la misma moneda no cambian el total de ninguna y no interesan al
	 * flujo de caja. Sin orden: lo decide la regla.
	 */
	@Query("""
			select t from Transfer t
			join fetch t.sourceAccount s
			join fetch t.targetAccount a
			where t.userId = :userId and t.transferDate >= :from and t.transferDate <= :to
				and s.currency <> a.currency""")
	List<Transfer> findCrossCurrencyForCashFlow(@Param("userId") Long userId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);
}
