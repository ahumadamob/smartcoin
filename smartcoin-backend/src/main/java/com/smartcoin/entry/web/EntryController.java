package com.smartcoin.entry.web;

import java.time.YearMonth;

import jakarta.validation.Valid;

import com.smartcoin.entry.service.EntryService;
import com.smartcoin.period.web.PeriodViewResponse.PeriodEntry;
import com.smartcoin.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Partidas", description = "Partidas sin Concepto: puntuales, de saldo postergado y de diferencia de "
		+ "cierre (RN-18, RN-19).")
@SecurityRequirement(name = "bearerAuth")
public class EntryController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final EntryService entries;
	private final CurrentUser currentUser;

	public EntryController(EntryService entries, CurrentUser currentUser) {
		this.entries = entries;
		this.currentUser = currentUser;
	}

	@PostMapping("/periods/{period}/entries")
	@Operation(operationId = "createOneOffEntry", summary = "Agregar una partida puntual",
			description = "Crea una partida de origen `ONE_OFF` en un período abierto. Figura solo en ese período: no "
					+ "se copia a otros ni toca ningún Concepto. Responde con la partida y sus valores derivados, con "
					+ "la misma forma que la vista del mes. Orden en que se evalúa: formato de la ruta y del cuerpo "
					+ "(400), período inexistente (404), período cerrado (409), y después vencimiento, cuenta y "
					+ "categoría (400).")
	@ApiResponse(responseCode = "201", description = "Partida creada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: el período de la ruta no tiene el formato "
			+ "YYYY-MM, faltan datos o tienen formato inválido, el vencimiento está fuera del rango (`dueDate`), o la "
			+ "cuenta o la categoría no existen para el usuario (`accountId`, `categoryId`). Trae `errors` por campo.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el período no existe para el usuario: es anterior a "
			+ "su período inicial o posterior al horizonte (RN-06).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período está cerrado (RN-09).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<PeriodEntry> create(
			@Parameter(description = "Período, como YYYY-MM.", example = "2026-11",
					schema = @Schema(type = "string", pattern = "^\\d{4}-(0[1-9]|1[0-2])$"))
			@PathVariable YearMonth period,
			@Valid @RequestBody OneOffEntryRequest request) {
		PeriodEntry created = PeriodEntry
				.from(entries.createOneOff(currentUser.id(), period, request.toValues()));
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@PatchMapping("/entries/{id}")
	@Operation(operationId = "updateEntry", summary = "Editar una partida sin Concepto",
			description = "Cambia nombre, categoría, cuenta, vencimiento y presupuestado de una partida pendiente de "
					+ "un período abierto. Solo cambia lo que se envía: un campo omitido o `null` no cambia, y la "
					+ "categoría se vacía con `clearCategory`. No marca la partida como editada y no toca ninguna "
					+ "otra. El tipo no se edita. Con movimientos, la cuenta solo cambia por otra de la misma "
					+ "moneda. Una partida recurrente toma sus datos de su Concepto: cualquier cambio es "
					+ "409 FIELD_NOT_EDITABLE. Orden en que se evalúa: formato del cuerpo (400), partida inexistente "
					+ "o ajena (404), período cerrado (409), partida consolidada (409), dato no editable (409), "
					+ "vencimiento, cuenta y categoría (400), y moneda con movimientos (409).")
	@ApiResponse(responseCode = "200", description = "Partida actualizada, con sus valores derivados.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: datos con formato inválido, categoría y "
			+ "`clearCategory` a la vez, vencimiento fuera del rango (`dueDate`), o cuenta o categoría que no existen "
			+ "para el usuario. Trae `errors` por campo.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período está cerrado (RN-09). "
			+ "ENTRY_NOT_PENDING: la partida está consolidada. FIELD_NOT_EDITABLE: se cambió el tipo, o algún dato "
			+ "de una partida recurrente. CURRENCY_MISMATCH: la partida tiene movimientos y la cuenta nueva es de "
			+ "otra moneda (RN-19).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public PeriodEntry update(@PathVariable long id, @Valid @RequestBody EntryUpdateRequest request) {
		return PeriodEntry.from(entries.update(currentUser.id(), id, request.toChanges()));
	}
}
