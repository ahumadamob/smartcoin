package com.smartcoin.movement.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import com.smartcoin.account.domain.AccountTotal;
import com.smartcoin.movement.domain.Movement;
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
}
