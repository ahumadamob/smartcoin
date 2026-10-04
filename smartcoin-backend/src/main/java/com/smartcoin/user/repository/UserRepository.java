package com.smartcoin.user.repository;

import java.util.Optional;

import com.smartcoin.user.domain.User;

import org.springframework.data.jpa.repository.JpaRepository;

/** Excepción a "todo método recibe el userId": {@link User} es el propio dueño de los datos. */
public interface UserRepository extends JpaRepository<User, Long> {

	/** El email se guarda en minúsculas y la colación de la columna no distingue mayúsculas. */
	boolean existsByEmail(String email);

	/** Se busca con el email en minúsculas; la colación de la columna no distingue mayúsculas. */
	Optional<User> findByEmail(String email);
}
