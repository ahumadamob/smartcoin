package com.smartcoin.category.service;

import java.util.List;
import java.util.Optional;

import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HU-09, RN-34 y RN-01: sin base de datos, con repositorios simulados. */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final long CATEGORY_ID = 42;

	@Mock
	CategoryRepository categories;
	@Mock
	BudgetItemRepository items;
	@Mock
	BudgetEntryRepository entries;

	CategoryService service;

	@BeforeEach
	void setUp() {
		service = new CategoryService(categories, items, entries);
	}

	private static Category category(String name) {
		Category category = new Category();
		ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
		category.setUserId(USER_ID);
		category.setName(name);
		return category;
	}

	private void ownCategory(String name) {
		when(categories.findByIdAndUserId(CATEGORY_ID, USER_ID)).thenReturn(Optional.of(category(name)));
	}

	private static void assertCode(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.code()).isEqualTo(code));
	}

	// --- alta

	@Test
	void createSavesTheCategoryForTheCurrentUserWithTheTrimmedName() {
		when(categories.existsByUserIdAndName(USER_ID, "Impuestos")).thenReturn(false);
		when(categories.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

		Category created = service.create(USER_ID, "  Impuestos \t");

		ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
		verify(categories).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(saved.getValue().getName()).isEqualTo("Impuestos");
		assertThat(created.getName()).isEqualTo("Impuestos");
	}

	@Test
	void createWithATakenNameIsRejectedAndNothingIsSaved() {
		when(categories.existsByUserIdAndName(USER_ID, "Hogar")).thenReturn(true);

		assertCode(() -> service.create(USER_ID, " Hogar "), ErrorCode.CATEGORY_NAME_TAKEN);

		verify(categories, never()).saveAndFlush(any());
	}

	@Test
	void createOnlyLooksForTheNameAmongTheCurrentUsersCategories() {
		// Dos usuarios distintos pueden usar el mismo nombre: la consulta lleva siempre el userId.
		when(categories.existsByUserIdAndName(OTHER_USER_ID, "Hogar")).thenReturn(false);
		when(categories.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

		service.create(OTHER_USER_ID, "Hogar");

		verify(categories).existsByUserIdAndName(OTHER_USER_ID, "Hogar");
		verify(categories, never()).existsByUserIdAndName(USER_ID, "Hogar");
	}

	@Test
	void createLosingTheRaceAgainstTheUniqueIndexIsANameTaken() {
		when(categories.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

		assertCode(() -> service.create(USER_ID, "Hogar"), ErrorCode.CATEGORY_NAME_TAKEN);
	}

	// --- renombrar

	@Test
	void renameChangesTheNameWithoutSpacesAtTheEnds() {
		ownCategory("Hogar");
		when(categories.existsByUserIdAndNameAndIdNot(USER_ID, "Casa", CATEGORY_ID)).thenReturn(false);
		when(categories.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

		Category renamed = service.update(USER_ID, CATEGORY_ID, " Casa ");

		assertThat(renamed.getName()).isEqualTo("Casa");
	}

	@Test
	void renameToANameTakenByAnotherCategoryIsRejected() {
		ownCategory("Hogar");
		when(categories.existsByUserIdAndNameAndIdNot(USER_ID, "Servicios", CATEGORY_ID)).thenReturn(true);

		assertCode(() -> service.update(USER_ID, CATEGORY_ID, "Servicios"), ErrorCode.CATEGORY_NAME_TAKEN);

		verify(categories, never()).saveAndFlush(any());
	}

	@Test
	void renameToItsOwnNameChangingOnlyTheCaseIsNotAConflict() {
		ownCategory("hogar");
		// La búsqueda de conflicto excluye la propia categoría: no encuentra a ninguna otra.
		when(categories.existsByUserIdAndNameAndIdNot(USER_ID, "HOGAR", CATEGORY_ID)).thenReturn(false);
		when(categories.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

		Category renamed = service.update(USER_ID, CATEGORY_ID, "HOGAR");

		assertThat(renamed.getName()).isEqualTo("HOGAR");
		verify(categories, never()).existsByUserIdAndName(any(), any());
	}

	@Test
	void renameToExactlyTheSameNameIsNotAConflict() {
		ownCategory("Hogar");
		when(categories.existsByUserIdAndNameAndIdNot(USER_ID, "Hogar", CATEGORY_ID)).thenReturn(false);
		when(categories.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

		assertThat(service.update(USER_ID, CATEGORY_ID, "Hogar").getName()).isEqualTo("Hogar");
	}

	@Test
	void renameLosingTheRaceAgainstTheUniqueIndexIsANameTaken() {
		ownCategory("Hogar");
		when(categories.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

		assertCode(() -> service.update(USER_ID, CATEGORY_ID, "Casa"), ErrorCode.CATEGORY_NAME_TAKEN);
	}

	// --- eliminar

	@Test
	void deleteAnUnusedCategoryRemovesIt() {
		ownCategory("Hogar");
		Category category = category("Hogar");
		when(categories.findByIdAndUserId(CATEGORY_ID, USER_ID)).thenReturn(Optional.of(category));

		service.delete(USER_ID, CATEGORY_ID);

		verify(items).existsByUserIdAndCategoryId(USER_ID, CATEGORY_ID);
		verify(entries).existsByUserIdAndCategoryId(USER_ID, CATEGORY_ID);
		verify(categories).delete(category);
	}

	@Test
	void deleteACategoryUsedByABudgetItemIsRejected() {
		ownCategory("Hogar");
		when(items.existsByUserIdAndCategoryId(USER_ID, CATEGORY_ID)).thenReturn(true);

		assertCode(() -> service.delete(USER_ID, CATEGORY_ID), ErrorCode.CATEGORY_IN_USE);

		verify(categories, never()).delete(any());
	}

	@Test
	void deleteACategoryUsedByABudgetEntryIsRejected() {
		ownCategory("Hogar");
		when(entries.existsByUserIdAndCategoryId(USER_ID, CATEGORY_ID)).thenReturn(true);

		assertCode(() -> service.delete(USER_ID, CATEGORY_ID), ErrorCode.CATEGORY_IN_USE);

		verify(categories, never()).delete(any());
	}

	@Test
	void deleteBlockedByTheForeignKeyIsAlsoInUse() {
		ownCategory("Hogar");
		doThrow(new DataIntegrityViolationException("fk")).when(categories).flush();

		assertCode(() -> service.delete(USER_ID, CATEGORY_ID), ErrorCode.CATEGORY_IN_USE);
	}

	// --- aislamiento (RN-01)

	@Test
	void anIdOfAnotherUserIsNotFoundWhenRenaming() {
		when(categories.findByIdAndUserId(CATEGORY_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

		assertCode(() -> service.update(OTHER_USER_ID, CATEGORY_ID, "Casa"), ErrorCode.NOT_FOUND);

		verify(categories, never()).saveAndFlush(any());
	}

	@Test
	void anIdOfAnotherUserIsNotFoundWhenDeleting() {
		when(categories.findByIdAndUserId(CATEGORY_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

		assertCode(() -> service.delete(OTHER_USER_ID, CATEGORY_ID), ErrorCode.NOT_FOUND);

		verify(categories, never()).delete(any());
	}

	@Test
	void listQueriesOnlyWithTheCurrentUserId() {
		Category hogar = category("Hogar");
		when(categories.findByUserIdOrderByNameAsc(USER_ID)).thenReturn(List.of(hogar));

		assertThat(service.list(USER_ID)).containsExactly(hogar);

		verify(categories).findByUserIdOrderByNameAsc(USER_ID);
		org.mockito.Mockito.verifyNoMoreInteractions(categories);
	}
}
