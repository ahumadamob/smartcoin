package com.smartcoin.movement.web;

import java.util.List;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/entries/{entryId}")
@Tag(name = "Movimientos", description = "Cobros y pagos reales de una partida (RN-21, RN-23).")
@SecurityRequirement(name = "bearerAuth")
public class MovementController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final MovementService movements;
	private final CurrentUser currentUser;

	public MovementController(MovementService movements, CurrentUser currentUser) {
		this.movements = movements;
		this.currentUser = currentUser;
	}

	@PostMapping("/movements")
	@Operation(operationId = "registerMovement", summary = "Registrar un cobro o pago",
			description = "Agrega un movimiento a una partida pendiente de un período abierto (RN-21). No "
					+ "consolida ni cambia el presupuestado, aunque el real alcance o supere al presupuestado "
					+ "(RN-23): la partida pasa a Parcial solo porque ahora tiene movimientos. Responde con el "
					+ "movimiento y la partida con sus valores derivados ya actualizados. Orden en que se evalúa, "
					+ "y se informa el primero que falla: formato del cuerpo (400), partida inexistente o ajena "
					+ "(404), período de la partida cerrado (409 PERIOD_CLOSED), partida consolidada (409 "
					+ "ENTRY_NOT_PENDING), cuenta inexistente o ajena (400 en `accountId`), moneda distinta (409 "
					+ "CURRENCY_MISMATCH) y fecha: anterior a la ventana de anticipación, a la apertura de la cuenta "
					+ "o posterior a hoy (409 DATE_OUT_OF_RANGE), y en un mes cerrado (409 PERIOD_CLOSED).")
	@ApiResponse(responseCode = "201", description = "Movimiento registrado.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: faltan datos, el monto no es mayor que 0 o "
			+ "tiene más de 2 decimales, la nota supera los 200 caracteres, o la cuenta no existe para el usuario "
			+ "(`accountId`). Trae `errors` por campo.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período de la partida o el mes de la fecha "
			+ "está cerrado (RN-09). ENTRY_NOT_PENDING: la partida está consolidada. CURRENCY_MISMATCH: la cuenta es "
			+ "de otra moneda que la partida. DATE_OUT_OF_RANGE: fecha antes de la ventana, antes de la apertura de "
			+ "la cuenta, futura, o en un mes anterior al período inicial.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<MovementRegisteredResponse> register(@PathVariable long entryId,
			@Valid @RequestBody MovementRequest request) {
		MovementRegisteredResponse created = MovementRegisteredResponse
				.from(movements.register(currentUser.id(), entryId, request.toValues()));
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/movements")
	@Operation(operationId = "listMovements", summary = "Listar los movimientos de una partida",
			description = "Los movimientos de la partida, por fecha y, a igual fecha, por orden de registro, cada "
					+ "uno con su cuenta. Sin paginación. Se pueden ver también los de una partida consolidada o de "
					+ "un período cerrado.")
	@ApiResponse(responseCode = "200", description = "Movimientos de la partida; vacía si no tiene.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public List<MovementResponse> list(@PathVariable long entryId) {
		return movements.list(currentUser.id(), entryId).stream().map(MovementResponse::from).toList();
	}

	@GetMapping("/movement-dates")
	@Operation(operationId = "getMovementDates", summary = "Fechas que admite un movimiento de la partida",
			description = "La fecha más temprana y la más tardía con que se puede registrar un movimiento en la "
					+ "partida (RN-21), y la ventana de anticipación en días. La más temprana es la más tardía "
					+ "entre el inicio de la ventana (primer día del período menos la ventana) y la apertura de la "
					+ "cuenta; la más tardía es hoy. Con `accountId` se considera esa cuenta; sin él, la cuenta "
					+ "prevista de la partida. Si la más temprana es posterior a la más tardía, la partida todavía "
					+ "no admite movimientos. Informa solo el rango: no mira el estado de la partida ni de los "
					+ "meses, que decide el registro.")
	@ApiResponse(responseCode = "200", description = "Rango de fechas.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: `accountId` no existe para el usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public MovementDatesResponse dates(@PathVariable long entryId,
			@RequestParam(required = false) Long accountId) {
		return MovementDatesResponse.from(movements.dates(currentUser.id(), entryId, accountId));
	}
}
