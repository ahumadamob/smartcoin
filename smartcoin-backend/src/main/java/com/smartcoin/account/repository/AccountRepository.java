package com.smartcoin.account.repository;

import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

/** Cuentas del usuario. Todo método recibe {@code userId}: no usar {@code findById} ni {@code findAll}. */
public interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByIdAndUserId(Long id, Long userId);

	List<Account> findByUserIdOrderByCurrencyAscNameAsc(@Param("userId") Long userId);

	/** La colación de la columna no distingue mayúsculas (ni espacios finales de más: el nombre se guarda recortado). */
	boolean existsByUserIdAndName(@Param("userId") Long userId, String name);

	boolean existsByUserIdAndNameAndIdNot(@Param("userId") Long userId, String name, Long id);
}
