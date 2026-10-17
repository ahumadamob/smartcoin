package com.smartcoin.period.web;

import java.time.YearMonth;

import com.smartcoin.period.service.ClosingService;
import com.smartcoin.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/periods")
@Tag(name = "Cierre de mes", description = "Simulación y cierre de un período (RN-38 a RN-43).")
@SecurityRequirement(name = "bearerAuth")
public class ClosingController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final ClosingService closing;
	private final CurrentUser currentUser;

	public ClosingController(ClosingService closing, CurrentUser currentUser) {
		this.closing = closing;
		this.currentUser = currentUser;
	}

	@PostMapping("/{period}/closing-preview")
	@Operation(operationId = "previewClosing", summary = "Simular el cierre de un mes",
			description = "Qué falta para cerrar el período, sin modificar nada (RN-43): si se cumplen las "
					+ "precondiciones de RN-38 y, si no, todas las que fallan, en el orden de RN-38; las partidas "
					+ "pendientes con real y pendiente, y las cuentas abiertas al último día del período con su "
					+ "saldo calculado a ese día (RN-35, RN-41). Con resoluciones en el cuerpo (opcional), lo que "
					+ "pasaría con cada pendiente resuelta y las partidas de saldo postergado que se crearían en el "
					+ "mes siguiente (RN-40, pasos 1 y 2), las pendientes que todavía no tienen resolución y las "
					+ "resoluciones que no corresponden, que no se aplican. Los efectos se calculan aunque no se "
					+ "cumplan las precondiciones. Con saldos reales (opcional), la diferencia de cada cuenta (real "
					+ "menos calculado), las partidas de diferencia de cierre que se crearían en el mes siguiente "
					+ "junto con las de saldo postergado (RN-40, paso 3), las cuentas que todavía no tienen saldo "
					+ "real y los saldos que no corresponden, que no se aplican. Un período cerrado responde 200 con "
					+ "`PERIOD_CLOSED` y las listas vacías, e ignora las resoluciones y los saldos.")
	@ApiResponse(responseCode = "200", description = "La simulación del cierre.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: el período no tiene el formato YYYY-MM; "
			+ "una resolución sin partida o sin valor, o con un valor inválido; una partida con más de una "
			+ "resolución; un saldo real sin cuenta, sin monto o con más de 2 decimales; o una cuenta con más de "
			+ "un saldo real.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el período no existe para el usuario: es anterior "
			+ "a su período inicial o posterior al horizonte (RN-06).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ClosingPreviewResponse preview(
			@Parameter(description = "Período, como YYYY-MM.", example = "2026-10",
					schema = @Schema(type = "string", pattern = "^\\d{4}-(0[1-9]|1[0-2])$"))
			@PathVariable YearMonth period,
			@RequestBody(required = false) @Valid ClosingPreviewRequest request) {
		return ClosingPreviewResponse.from(closing.preview(currentUser.id(), period,
				ClosingPreviewRequest.resolutionsOf(request), ClosingPreviewRequest.realBalancesOf(request)));
	}

	@PostMapping("/{period}/close")
	@Operation(operationId = "closePeriod", summary = "Cerrar un mes",
			description = "Cierra el período para siempre (RN-40, D-23): no hay forma de reabrirlo. Recibe el mismo "
					+ "cuerpo que la simulación y vuelve a hacer todas sus validaciones con el mismo plan (RN-43); "
					+ "sin cuerpo, solo se cierra un mes sin pendientes ni cuentas a conciliar. En una sola "
					+ "transacción, y en este orden: consolida cada pendiente con su real y su resolución, sin "
					+ "propagar ni tocar el Concepto; crea las partidas de saldo postergado en el mes siguiente; "
					+ "guarda un cierre de cuenta por cada cuenta conciliada; crea las partidas de diferencia de "
					+ "cierre, referidas a su cierre de cuenta; y pasa el período a cerrado con su fecha de cierre. "
					+ "Si algo falla, no cambia nada. Orden de los errores, el primero que falla: cuerpo mal formado "
					+ "(400); período inexistente (404); la primera precondición de RN-38 (409 `PERIOD_CLOSED`, "
					+ "`PREVIOUS_PERIOD_OPEN` o `PERIOD_NOT_FINISHED`); resoluciones que no corresponden a una "
					+ "pendiente del período (400 en `resolutions`); saldos reales de cuentas que no se concilian "
					+ "(400 en `realBalances`); pendientes sin resolución (409 `UNRESOLVED_PENDING_ENTRIES`, con sus "
					+ "ids en `entries`); y cuentas sin saldo real (409 `MISSING_REAL_BALANCE`, con sus ids en "
					+ "`accounts`).")
	@ApiResponse(responseCode = "200", description = "El período quedó cerrado: lo que aplicó el cierre.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: el período no tiene el formato YYYY-MM; un "
			+ "cuerpo mal formado, como en la simulación; una resolución para una partida que no es una pendiente "
			+ "del período (en `resolutions`); o un saldo real para una cuenta que no se concilia (en "
			+ "`realBalances`).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el período no existe para el usuario (RN-06).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período ya está cerrado. "
			+ "PREVIOUS_PERIOD_OPEN: el anterior está abierto. PERIOD_NOT_FINISHED: hoy es anterior a su último "
			+ "día. UNRESOLVED_PENDING_ENTRIES: falta la resolución de alguna pendiente (`entries`). "
			+ "MISSING_REAL_BALANCE: falta el saldo real de alguna cuenta (`accounts`).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ClosingResultResponse close(
			@Parameter(description = "Período, como YYYY-MM.", example = "2026-10",
					schema = @Schema(type = "string", pattern = "^\\d{4}-(0[1-9]|1[0-2])$"))
			@PathVariable YearMonth period,
			@RequestBody(required = false) @Valid ClosingPreviewRequest request) {
		return ClosingResultResponse.from(closing.close(currentUser.id(), period,
				ClosingPreviewRequest.resolutionsOf(request), ClosingPreviewRequest.realBalancesOf(request)));
	}
}
