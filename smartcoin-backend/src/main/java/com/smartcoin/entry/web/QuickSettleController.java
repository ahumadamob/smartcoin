package com.smartcoin.entry.web;

import com.smartcoin.entry.service.QuickSettleService;
import com.smartcoin.entry.service.QuickSettleService.Request;
import com.smartcoin.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/entries/{id}")
@Tag(name = "Movimientos", description = "Cobros y pagos reales de una partida (RN-21, RN-23).")
@SecurityRequirement(name = "bearerAuth")
public class QuickSettleController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final QuickSettleService quickSettle;
	private final CurrentUser currentUser;

	public QuickSettleController(QuickSettleService quickSettle, CurrentUser currentUser) {
		this.quickSettle = quickSettle;
		this.currentUser = currentUser;
	}

	@GetMapping("/quick-settle-preview")
	@Operation(operationId = "getQuickSettlePreview", summary = "Vista previa del pago rápido de una partida",
			description = "Informa, sin modificar nada, qué pasaría al pagar o cobrar todo lo pendiente: el monto "
					+ "del movimiento (el pendiente) y la consolidación que le sigue, con su nueva estimación y las "
					+ "partidas de destino. Sale del mismo plan que `POST /api/entries/{id}/quick-settle`. Si no se "
					+ "puede responde 200 con `allowed` en falso y el motivo.")
	@ApiResponse(responseCode = "200", description = "Vista previa del pago rápido.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public QuickSettlePreviewResponse preview(@PathVariable long id) {
		return QuickSettlePreviewResponse.from(quickSettle.preview(currentUser.id(), id));
	}

	@PostMapping("/quick-settle")
	@Operation(operationId = "quickSettleEntry", summary = "Pagar o cobrar todo lo pendiente de una partida",
			description = "Registra un movimiento por exactamente el pendiente y consolida la partida, todo en una "
					+ "sola transacción (RN-24): con un error no queda ni el movimiento ni la consolidación. El "
					+ "movimiento cumple RN-21 y la consolidación RN-25 y RN-26, incluida la política para partidas "
					+ "editadas. Sin fecha, hoy; sin cuenta, la de la partida. Orden en que se evalúa, y se informa "
					+ "el primero que falla: formato del cuerpo (400), partida inexistente o ajena (404), período "
					+ "cerrado (409 PERIOD_CLOSED), partida consolidada (409 ENTRY_NOT_PENDING), sin pendiente (409 "
					+ "NOTHING_PENDING), cuenta inexistente o ajena (400 en `accountId`), moneda distinta (409 "
					+ "CURRENCY_MISMATCH), fecha (409 DATE_OUT_OF_RANGE, y 409 PERIOD_CLOSED si el mes está "
					+ "cerrado) y política faltante (409 MANUAL_POLICY_REQUIRED).")
	@ApiResponse(responseCode = "200", description = "Movimiento registrado y partida consolidada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: cuerpo con un valor inválido o la cuenta no "
			+ "existe para el usuario (`accountId`).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período de la partida o el mes de la fecha "
			+ "está cerrado (RN-09). ENTRY_NOT_PENDING: ya está consolidada. NOTHING_PENDING: no hay pendiente. "
			+ "CURRENCY_MISMATCH: la cuenta es de otra moneda. DATE_OUT_OF_RANGE: fecha antes de la ventana, antes "
			+ "de la apertura de la cuenta o futura. MANUAL_POLICY_REQUIRED: hay editadas en el destino y no se "
			+ "indicó política; `entries` trae sus ids.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public QuickSettleResponse settle(@PathVariable long id,
			@RequestBody(required = false) QuickSettleRequest request) {
		Request values = request == null ? new Request(null, null, null) : request.toRequest();
		return QuickSettleResponse.from(quickSettle.quickSettle(currentUser.id(), id, values));
	}
}
