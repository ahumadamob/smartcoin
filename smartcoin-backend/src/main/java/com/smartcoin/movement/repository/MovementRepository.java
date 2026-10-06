package com.smartcoin.movement.repository;

import java.time.LocalDate;

import com.smartcoin.movement.domain.Movement;

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
}
