package com.smartcoin.budgetitem.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.smartcoin.account.domain.FieldEditability;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

/**
 * Qué datos de un Concepto no se pueden editar (RN-15, S-12): tipo, periodicidad, período de inicio, período de fin
 * y cuotas. A diferencia de una cuenta, no dependen del estado: nunca se editan. Para cambiarlos se da de baja el
 * Concepto y se crea otro. Regla pura: recibe valores y devuelve resultados.
 */
public final class BudgetItemEditability {

	/** Un dato que no se edita, con su nombre para el mensaje y el motivo que ve el usuario. */
	public enum LockedField {
		KIND("el tipo", "El tipo no se puede cambiar: para cambiarlo, dá de baja el Concepto y creá otro."),
		PERIODICITY("la periodicidad",
				"La periodicidad no se puede cambiar: para cambiarla, dá de baja el Concepto y creá otro."),
		START_PERIOD("el período de inicio",
				"El período de inicio no se puede cambiar: para cambiarlo, dá de baja el Concepto y creá otro."),
		END_PERIOD("el período de fin",
				"El período de fin no se puede cambiar: para cambiarlo, dá de baja el Concepto y creá otro."),
		INSTALLMENTS("las cuotas",
				"Las cuotas no se pueden cambiar: para cambiarlas, dá de baja el Concepto y creá otro.");

		private final String label;
		private final String reason;

		LockedField(String label, String reason) {
			this.label = label;
			this.reason = reason;
		}

		public String reason() {
			return reason;
		}
	}

	private BudgetItemEditability() {
	}

	/** El estado de edición de un dato bloqueado: nunca es editable, con su motivo. */
	public static FieldEditability of(LockedField field) {
		return new FieldEditability(false, field.reason);
	}

	/**
	 * Los datos bloqueados que el pedido cambia, en el orden de {@link LockedField}. Enviar el mismo valor que ya
	 * tiene no es un cambio. En un Concepto en cuotas el fin se calcula (RN-14): si el pedido no lo informa, no cuenta
	 * como cambio. Una primera cuota vacía vale 1, como al crear.
	 */
	public static List<LockedField> changedLockedFields(BudgetItemValues current, BudgetItemValues requested) {
		return Arrays.stream(LockedField.values()).filter(field -> changed(field, current, requested)).toList();
	}

	/**
	 * Verifica que el pedido no cambie ningún dato bloqueado.
	 *
	 * @throws BusinessException {@code FIELD_NOT_EDITABLE} con el motivo, o con la lista si cambian varios
	 */
	public static void verifyChange(BudgetItemValues current, BudgetItemValues requested) {
		List<LockedField> changed = changedLockedFields(current, requested);
		if (changed.isEmpty()) {
			return;
		}
		if (changed.size() == 1) {
			throw notEditable(changed.getFirst().reason);
		}
		List<String> labels = changed.stream().map(field -> field.label).toList();
		String list = String.join(", ", labels.subList(0, labels.size() - 1)) + " y " + labels.getLast();
		throw notEditable("No se pueden cambiar " + list + ": para modificarlos, dá de baja el Concepto y creá otro.");
	}

	private static boolean changed(LockedField field, BudgetItemValues current, BudgetItemValues requested) {
		return switch (field) {
			case KIND -> current.kind() != requested.kind();
			case PERIODICITY -> current.periodicity() != requested.periodicity();
			case START_PERIOD -> !current.startPeriod().equals(requested.startPeriod());
			case END_PERIOD -> !(current.installmentsTotal() != null && requested.endPeriod() == null)
					&& !Objects.equals(current.endPeriod(), requested.endPeriod());
			case INSTALLMENTS -> !Objects.equals(current.installmentsTotal(), requested.installmentsTotal())
					|| !Objects.equals(firstInstallment(current), firstInstallment(requested));
		};
	}

	private static Integer firstInstallment(BudgetItemValues values) {
		if (values.installmentsTotal() == null) {
			return values.firstInstallmentNumber();
		}
		return values.firstInstallmentNumber() == null ? Integer.valueOf(1) : values.firstInstallmentNumber();
	}

	private static BusinessException notEditable(String reason) {
		return new BusinessException(ErrorCode.FIELD_NOT_EDITABLE, reason);
	}
}
