package com.smartcoin.category.service;

import java.util.List;

import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrar categorías (HU-09, RN-34). Toda consulta lleva el {@code userId} del usuario actual. */
@Service
public class CategoryService {

	private final CategoryRepository categories;
	private final BudgetItemRepository items;
	private final BudgetEntryRepository entries;

	public CategoryService(CategoryRepository categories, BudgetItemRepository items, BudgetEntryRepository entries) {
		this.categories = categories;
		this.items = items;
		this.entries = entries;
	}

	@Transactional(readOnly = true)
	public List<Category> list(long userId) {
		return categories.findByUserIdOrderByNameAsc(userId);
	}

	@Transactional
	public Category create(long userId, String name) {
		String normalized = name.strip();
		if (categories.existsByUserIdAndName(userId, normalized)) {
			throw nameTaken();
		}
		Category category = new Category();
		category.setUserId(userId);
		category.setName(normalized);
		return save(category);
	}

	@Transactional
	public Category update(long userId, long id, String name) {
		Category category = find(userId, id);
		String normalized = name.strip();
		// Renombrar a su mismo nombre (aunque cambien las mayúsculas) no es un conflicto: se excluye a sí misma.
		if (categories.existsByUserIdAndNameAndIdNot(userId, normalized, id)) {
			throw nameTaken();
		}
		category.setName(normalized);
		return save(category);
	}

	@Transactional
	public void delete(long userId, long id) {
		Category category = find(userId, id);
		if (items.existsByUserIdAndCategoryId(userId, id) || entries.existsByUserIdAndCategoryId(userId, id)) {
			throw inUse();
		}
		try {
			categories.delete(category);
			categories.flush();
		}
		catch (DataIntegrityViolationException e) {
			// Una referencia creada entre la verificación y el borrado: la clave foránea la frena.
			throw inUse();
		}
	}

	private Category find(long userId, long id) {
		return categories.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La categoría no existe."));
	}

	private Category save(Category category) {
		try {
			return categories.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException e) {
			// Otro pedido creó el mismo nombre entre la verificación y el guardado: lo frena el índice único.
			throw nameTaken();
		}
	}

	private static BusinessException nameTaken() {
		return new BusinessException(ErrorCode.CATEGORY_NAME_TAKEN, "Ya tenés una categoría con ese nombre.");
	}

	private static BusinessException inUse() {
		return new BusinessException(ErrorCode.CATEGORY_IN_USE,
				"No se puede eliminar la categoría porque la usan Conceptos o partidas.");
	}
}
