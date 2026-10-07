package com.smartcoin.budgetitem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import com.smartcoin.budgetitem.domain.BudgetItemEditability.LockedField;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** RN-15 y S-12: tipo, periodicidad, inicio, fin y cuotas no se editan; el resto sí. */
class BudgetItemEditabilityTest {

	static final YearMonth START = YearMonth.of(2026, 10);

	/** Mensual, sin fin y sin cuotas. */
	static final BudgetItemValues RECURRING = new BudgetItemValues("Monotributo", EntryKind.EXPENSE, 12L, 3L,
			Periodicity.MONTHLY, 20, 0, START, null, EstimationRule.LAST_VALUE, new BigDecimal("85000.00"));

	/** Heladera: 12 cuotas, primera 4, inicio 2026-10, fin calculado 2027-06. */
	static final BudgetItemValues INSTALLMENTS = new BudgetItemValues("Heladera", EntryKind.EXPENSE, 12L, null,
			Periodicity.MONTHLY, 5, 0, START, YearMonth.of(2027, 6), EstimationRule.LAST_VALUE,
			new BigDecimal("50000.00"), 12, 4);

	private static BudgetItemValues copy(BudgetItemValues v, UnaryOperator<Builder> change) {
		return change.apply(new Builder(v)).build();
	}

	/** Un {@link BudgetItemValues} editable campo por campo. */
	static final class Builder {
		String name;
		EntryKind kind;
		Long accountId;
		Long categoryId;
		Periodicity periodicity;
		int dueDay;
		int offset;
		YearMonth start;
		YearMonth end;
		EstimationRule rule;
		BigDecimal amount;
		Integer total;
		Integer first;

		Builder(BudgetItemValues v) {
			name = v.name();
			kind = v.kind();
			accountId = v.defaultAccountId();
			categoryId = v.categoryId();
			periodicity = v.periodicity();
			dueDay = v.dueDay();
			offset = v.dueMonthOffset();
			start = v.startPeriod();
			end = v.endPeriod();
			rule = v.estimationRule();
			amount = v.currentAmount();
			total = v.installmentsTotal();
			first = v.firstInstallmentNumber();
		}

		BudgetItemValues build() {
			return new BudgetItemValues(name, kind, accountId, categoryId, periodicity, dueDay, offset, start, end,
					rule, amount, total, first);
		}
	}

	static Stream<Arguments> lockedChanges() {
		return Stream.of(
				Arguments.of(LockedField.KIND, RECURRING, (UnaryOperator<Builder>) b -> {
					b.kind = EntryKind.INCOME;
					return b;
				}),
				Arguments.of(LockedField.PERIODICITY, RECURRING, (UnaryOperator<Builder>) b -> {
					b.periodicity = Periodicity.ANNUAL;
					return b;
				}),
				Arguments.of(LockedField.START_PERIOD, RECURRING, (UnaryOperator<Builder>) b -> {
					b.start = START.plusMonths(1);
					return b;
				}),
				Arguments.of(LockedField.END_PERIOD, RECURRING, (UnaryOperator<Builder>) b -> {
					b.end = YearMonth.of(2027, 12);
					return b;
				}),
				Arguments.of(LockedField.END_PERIOD, INSTALLMENTS, (UnaryOperator<Builder>) b -> {
					b.end = YearMonth.of(2027, 7);
					return b;
				}),
				Arguments.of(LockedField.INSTALLMENTS, RECURRING, (UnaryOperator<Builder>) b -> {
					b.total = 6;
					b.first = 1;
					return b;
				}),
				Arguments.of(LockedField.INSTALLMENTS, INSTALLMENTS, (UnaryOperator<Builder>) b -> {
					b.total = 24;
					return b;
				}),
				Arguments.of(LockedField.INSTALLMENTS, INSTALLMENTS, (UnaryOperator<Builder>) b -> {
					b.first = 5;
					return b;
				}),
				Arguments.of(LockedField.INSTALLMENTS, INSTALLMENTS, (UnaryOperator<Builder>) b -> {
					b.total = null;
					b.first = null;
					return b;
				}));
	}

	@ParameterizedTest
	@MethodSource("lockedChanges")
	void changingALockedFieldIsRejectedWithItsReason(LockedField field, BudgetItemValues current,
			UnaryOperator<Builder> change) {
		BudgetItemValues requested = copy(current, change);

		assertThat(BudgetItemEditability.changedLockedFields(current, requested)).contains(field);
		assertThatThrownBy(() -> BudgetItemEditability.verifyChange(current, requested))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_EDITABLE);
					assertThat(e.getMessage()).contains("dá de baja el Concepto");
				});
	}

	static Stream<Arguments> editableChanges() {
		return Stream.of(
				Arguments.of("nombre", (UnaryOperator<Builder>) b -> {
					b.name = "Otro nombre";
					return b;
				}),
				Arguments.of("categoría", (UnaryOperator<Builder>) b -> {
					b.categoryId = 99L;
					return b;
				}),
				Arguments.of("quitar la categoría", (UnaryOperator<Builder>) b -> {
					b.categoryId = null;
					return b;
				}),
				Arguments.of("cuenta por defecto", (UnaryOperator<Builder>) b -> {
					b.accountId = 13L;
					return b;
				}),
				Arguments.of("día de vencimiento", (UnaryOperator<Builder>) b -> {
					b.dueDay = 31;
					return b;
				}),
				Arguments.of("desfase de mes", (UnaryOperator<Builder>) b -> {
					b.offset = -1;
					return b;
				}),
				Arguments.of("regla de estimación", (UnaryOperator<Builder>) b -> {
					b.rule = EstimationRule.AVERAGE_LAST_3;
					return b;
				}),
				Arguments.of("monto vigente", (UnaryOperator<Builder>) b -> {
					b.amount = new BigDecimal("1.00");
					return b;
				}));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("editableChanges")
	void editableFieldsChangeFreelyInAnyKindOfItem(String field, UnaryOperator<Builder> change) {
		assertThatCode(() -> BudgetItemEditability.verifyChange(RECURRING, copy(RECURRING, change)))
				.doesNotThrowAnyException();
		assertThatCode(() -> BudgetItemEditability.verifyChange(INSTALLMENTS, copy(INSTALLMENTS, change)))
				.doesNotThrowAnyException();
	}

	@Test
	void sendingTheSameValuesIsNotAChange() {
		assertThat(BudgetItemEditability.changedLockedFields(RECURRING, RECURRING)).isEmpty();
		assertThat(BudgetItemEditability.changedLockedFields(INSTALLMENTS, INSTALLMENTS)).isEmpty();
	}

	@Test
	void anInstallmentItemMayOmitItsCalculatedEndOrEmptyFirstInstallment() {
		BudgetItemValues withoutEnd = copy(INSTALLMENTS, b -> {
			b.end = null;
			return b;
		});
		assertThat(BudgetItemEditability.changedLockedFields(INSTALLMENTS, withoutEnd)).isEmpty();

		// Un plan que empieza en la cuota 1 puede enviar la primera cuota vacía: vale 1, como al crear.
		BudgetItemValues fromOne = copy(INSTALLMENTS, b -> {
			b.first = 1;
			return b;
		});
		BudgetItemValues emptyFirst = copy(fromOne, b -> {
			b.first = null;
			return b;
		});
		assertThat(BudgetItemEditability.changedLockedFields(fromOne, emptyFirst)).isEmpty();
	}

	@Test
	void aRecurringItemCannotGainAnEndByOmittingItNorLoseIt() {
		BudgetItemValues withEnd = copy(RECURRING, b -> {
			b.end = YearMonth.of(2027, 12);
			return b;
		});

		assertThat(BudgetItemEditability.changedLockedFields(withEnd, RECURRING)).containsExactly(LockedField.END_PERIOD);
		assertThat(BudgetItemEditability.changedLockedFields(RECURRING, withEnd)).containsExactly(LockedField.END_PERIOD);
	}

	@Test
	void aFirstInstallmentWithoutTotalIsAChange() {
		BudgetItemValues requested = copy(RECURRING, b -> {
			b.first = 4;
			return b;
		});

		assertThat(BudgetItemEditability.changedLockedFields(RECURRING, requested)).containsExactly(LockedField.INSTALLMENTS);
	}

	@Test
	void severalLockedChangesAreListedTogetherInTheOrderOfTheFields() {
		BudgetItemValues requested = copy(RECURRING, b -> {
			b.kind = EntryKind.INCOME;
			b.periodicity = Periodicity.ANNUAL;
			b.start = START.plusMonths(2);
			return b;
		});

		assertThat(BudgetItemEditability.changedLockedFields(RECURRING, requested)).containsExactly(LockedField.KIND,
				LockedField.PERIODICITY, LockedField.START_PERIOD);
		assertThatThrownBy(() -> BudgetItemEditability.verifyChange(RECURRING, requested))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage())
						.isEqualTo("No se pueden cambiar el tipo, la periodicidad y el período de inicio: "
								+ "para modificarlos, dá de baja el Concepto y creá otro."));
	}

	@Test
	void lockedChangesAreRejectedEvenWhenEditableOnesChangeToo() {
		BudgetItemValues requested = copy(RECURRING, b -> {
			b.name = "Otro";
			b.amount = new BigDecimal("1.00");
			b.kind = EntryKind.INCOME;
			return b;
		});

		assertThat(BudgetItemEditability.changedLockedFields(RECURRING, requested)).isEqualTo(List.of(LockedField.KIND));
	}

	@Test
	void everyLockedFieldReportsItselfAsNotEditableWithAReason() {
		for (LockedField field : LockedField.values()) {
			assertThat(BudgetItemEditability.of(field).editable()).isFalse();
			assertThat(BudgetItemEditability.of(field).reason()).contains("dá de baja el Concepto");
		}
	}
}
