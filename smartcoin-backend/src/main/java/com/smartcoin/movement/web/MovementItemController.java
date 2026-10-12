package com.smartcoin.movement.web;

import jakarta.validation.Valid;

import com.smartcoin.movement.service.MovementService;
import com.smartcoin.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Corregir o eliminar un movimiento ya registrado (HU-21, RN-22). Registrar y listar están en el de la partida. */
@RestController
@RequestMapping("/api/movements/{id}")
@Tag(name = "Movimientos", description = "Cobros y pagos reales de una partida (RN-21, RN-23).")
@SecurityRequirement(name = "bearerAuth")
public class MovementItemController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final MovementService movements;
	private final CurrentUser currentUser;

	public MovementItemController(MovementService movements, CurrentUser currentUser) {
		this.movements = movements;
		this.currentUser = currentUser;
	}

	@PutMapping
	@Operation(operationId = "updateMovement", summary = "Corregir un movimiento",
			description = "Reemplaza fecha, monto, cuenta y nota de un movimiento (RN-22). La partida no cambia: "
					+ "un movimiento no se mueve de una partida a otra. Pide que la partida esté pendiente en un "
					+ "período abierto y que el mes de la fecha actual del movimiento esté abierto; los valores "
					+ "nuevos cumplen RN-21, con las mismas respuestas que el alta. Si los valores son los que ya "
					+ "tiene, responde 200 sin guardar nada. La fecha y la cuenta se revalidan solo si cambian. "
					+ "Orden en que se evalúa, y se informa el primero que falla: formato del cuerpo (400), "
					+ "movimiento inexistente o ajeno (404), período de la partida cerrado (409 PERIOD_CLOSED), "
					+ "partida consolidada (409 ENTRY_NOT_PENDING), mes de la fecha actual cerrado (409 "
					+ "PERIOD_CLOSED), cuenta inexistente o ajena (400 en `accountId`), moneda distinta (409 "
					+ "CURRENCY_MISMATCH) y fecha nueva: anterior a la ventana de anticipación, a la apertura de "
					+ "la cuenta o posterior a hoy (409 DATE_OUT_OF_RANGE), y en un mes cerrado (409 "
					+ "PERIOD_CLOSED). Responde con el movimiento y la partida con sus valores derivados.")
	@ApiResponse(responseCode = "200", description = "Movimiento corregido.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: faltan datos, el monto no es mayor que 0 o "
			+ "tiene más de 2 decimales, la nota supera los 200 caracteres, o la cuenta no existe para el usuario "
			+ "(`accountId`). Trae `errors` por campo.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el movimiento no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período de la partida, el mes de la fecha "
			+ "actual del movimiento o el de la fecha nueva está cerrado (RN-09). ENTRY_NOT_PENDING: la partida "
			+ "está consolidada. CURRENCY_MISMATCH: la cuenta es de otra moneda que la partida. DATE_OUT_OF_RANGE: "
			+ "fecha nueva antes de la ventana, antes de la apertura de la cuenta, futura, o en un mes anterior al "
			+ "período inicial.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public MovementRegisteredResponse update(@PathVariable long id, @Valid @RequestBody MovementRequest request) {
		return MovementRegisteredResponse.from(movements.update(currentUser.id(), id, request.toValues()));
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteMovement", summary = "Eliminar un movimiento",
			description = "Elimina un movimiento (RN-22). Mismas condiciones sobre el movimiento actual que al "
					+ "corregirlo: partida pendiente en un período abierto y mes de su fecha abierto. No toca la "
					+ "partida: si era su último movimiento, vuelve a mostrarse Estimada porque ya no tiene.")
	@ApiResponse(responseCode = "204", description = "Movimiento eliminado.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el movimiento no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período de la partida o el mes de la fecha "
			+ "del movimiento está cerrado (RN-09). ENTRY_NOT_PENDING: la partida está consolidada.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public void delete(@PathVariable long id) {
		movements.delete(currentUser.id(), id);
	}
}
