package com.smartcoin.category.repository;

import java.util.List;
import java.util.Optional;

import com.smartcoin.category.domain.Category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

/** Categorías del usuario. Todo método recibe {@code userId}: no usar {@code findById} ni {@code findAll}. */
public interface CategoryRepository extends JpaRepository<Category, Long> {

	Optional<Category> findByIdAndUserId(Long id, Long userId);

	List<Category> findByUserIdOrderByNameAsc(@Param("userId") Long userId);

	/** La colación de la columna no distingue mayúsculas (ni espacios finales de más: el nombre se guarda recortado). */
	boolean existsByUserIdAndName(@Param("userId") Long userId, String name);

	boolean existsByUserIdAndNameAndIdNot(@Param("userId") Long userId, String name, Long id);
}
