package com.smartcoin.entry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RN-16 y RN-17: real, pendiente, estimado y estado mostrado. */
class EntryAmountsTest {

	private static BigDecimal amount(String text) {
		return text == null ? null : new BigDecimal(text);
	}

	@ParameterizedTest(name = "{0}: presupuestado {1}, {2}, consolidado {3}, movimientos {4} → real {5}, pendiente {6}, estimado {7}, {8}")
	@CsvSource(nullValues = "-", value = {
			// Ejemplo de RN-17: Luz de 45.000,00 con un pago de 20.000,00.
			"Luz con pago parcial,          45000.00,   PENDING,      -,          20000.00,   20000.00,   25000.00,  45000.00,   PARTIAL",
			// Ejemplo de RN-17: si se pagaron 50.000,00, pendiente 0 y estimado 50.000,00.
			"Luz pagada de más,             45000.00,   PENDING,      -,          50000.00,   50000.00,   0.00,      50000.00,   PARTIAL",
			"Sin movimientos,               240000.00,  PENDING,      -,          -,          0.00,       240000.00, 240000.00,  ESTIMATED",
			"Suma en cero,                  240000.00,  PENDING,      -,          0.00,       0.00,       240000.00, 240000.00,  ESTIMATED",
			// RN-23: registrar no consolida, aunque el real alcance al presupuestado.
			"Real igual al presupuestado,   45000.00,   PENDING,      -,          45000.00,   45000.00,   0.00,      45000.00,   PARTIAL",
			"Consolidada con real menor,    45000.00,   CONSOLIDATED, 20000.00,   20000.00,   20000.00,   0.00,      20000.00,   CONSOLIDATED",
			"Consolidada con real mayor,    45000.00,   CONSOLIDATED, 50000.00,   50000.00,   50000.00,   0.00,      50000.00,   CONSOLIDATED",
			"Consolidada con real igual,    1200000.00, CONSOLIDATED, 1200000.00, 1200000.00, 1200000.00, 0.00,      1200000.00, CONSOLIDATED",
			// Cerrar con lo registrado sin movimientos (RN-40): consolidada en 0.
			"Consolidada sin movimientos,   45000.00,   CONSOLIDATED, 0.00,       -,          0.00,       0.00,      0.00,       CONSOLIDATED",
			"Presupuestado 0 sin pagos,     0.00,       PENDING,      -,          -,          0.00,       0.00,      0.00,       ESTIMATED",
			"Presupuestado 0 con un pago,   0.00,       PENDING,      -,          1500.50,    1500.50,    0.00,      1500.50,    PARTIAL",
			"Centavos,                      100.10,     PENDING,      -,          33.37,      33.37,      66.73,     100.10,     PARTIAL",
	})
	void derivedValues(String label, String budgeted, StoredEntryStatus stored, String consolidated, String movements,
			String actual, String pending, String forecast, EntryStatus status) {
		EntryAmounts amounts = EntryAmounts.of(amount(budgeted), stored, amount(consolidated), amount(movements));

		// isEqualTo y no isEqualByComparingTo: los montos salen siempre con 2 decimales (RN-03).
		assertThat(amounts.actual()).isEqualTo(amount(actual));
		assertThat(amounts.pending()).isEqualTo(amount(pending));
		assertThat(amounts.forecast()).isEqualTo(amount(forecast));
		assertThat(amounts.status()).isEqualTo(status);
	}
}
