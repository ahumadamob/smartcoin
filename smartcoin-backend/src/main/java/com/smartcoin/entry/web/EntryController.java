package com.smartcoin.entry.web;

import java.time.YearMonth;

import jakarta.validation.Valid;

import com.smartcoin.entry.domain.DeletionScope;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Partidas", description = "Alta y edición de partidas (RN-18, RN-19) y eliminación con alcance "
		+ "(RN-30 a RN-32).")
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
	@Operation(operationId = "updateEntry", summary = "Editar una partida",
			description = "Cambia una partida pendiente de un período abierto. En una partida sin Concepto edita "
					+ "nombre, categoría, cuenta, vencimiento y presupuestado, y no la marca como editada. En una "
					+ "recurrente edita solo el presupuestado (≥ 0) y la marca como editada, para que un cambio del "
					+ "monto vigente del Concepto no la pise; enviar el mismo monto no cambia nada, y cualquier "
					+ "otro dato (incluso junto con el presupuestado) es 409 FIELD_NOT_EDITABLE y no cambia nada. "
					+ "No toca otras partidas ni el Concepto. Solo cambia lo que se envía: un campo omitido o `null` "
					+ "no cambia, y la categoría se vacía con `clearCategory`. El tipo no se edita. Con "
					+ "movimientos, la cuenta solo cambia por otra de la misma moneda. Orden en que se evalúa: formato del cuerpo (400), partida inexistente "
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
			+ "de una partida recurrente que no sea el presupuestado. CURRENCY_MISMATCH: la partida tiene movimientos y la cuenta nueva es de "
			+ "otra moneda (RN-19).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public PeriodEntry update(@PathVariable long id, @Valid @RequestBody EntryUpdateRequest request) {
		return PeriodEntry.from(entries.update(currentUser.id(), id, request.toChanges()));
	}

	@DeleteMapping("/entries/{id}")
	@Operation(operationId = "deleteEntry", summary = "Eliminar una partida",
			description = "Elimina una partida pendiente y sin movimientos de un período abierto (RN-30, RN-31, "
					+ "RN-32). Una partida sin Concepto no lleva `scope`. Una recurrente lo exige: `ONLY_THIS` "
					+ "elimina solo esa partida y el Concepto sigue igual (la partida no reaparece al asegurar el "
					+ "horizonte, RN-13); `THIS_AND_FUTURE` elimina esa y todas las posteriores del Concepto y fija "
					+ "su fin en el mes anterior. El Concepto también se elimina si queda sin ninguna partida y sin "
					+ "nada por generar. Todo o nada: si alguna partida del alcance lo impide, no se elimina ni se "
					+ "modifica nada. Orden en que se evalúa: valor de `scope` inválido (400), partida inexistente o "
					+ "ajena (404), alcance faltante o sobrante (400), período cerrado (409) y partidas del alcance "
					+ "consolidadas (409 ENTRY_NOT_PENDING) o con movimientos (409 ENTRY_HAS_MOVEMENTS). "
					+ "`GET /api/entries/{id}/deletion-preview` informa de antemano qué haría cada alcance.")
	@ApiResponse(responseCode = "204", description = "Partida eliminada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: `scope` con un valor inválido; falta en una "
			+ "partida recurrente o se envió en una sin Concepto (en este caso, con `errors` en `scope`).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período de la partida está cerrado (RN-09). "
			+ "ENTRY_NOT_PENDING: alguna partida del alcance está consolidada. ENTRY_HAS_MOVEMENTS: alguna "
			+ "partida del alcance tiene movimientos. En los dos últimos, `entries` trae los ids de todas las que "
			+ "impiden y no se elimina nada.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<Void> delete(@PathVariable long id,
			@Parameter(description = "Alcance. Obligatorio en una partida recurrente; no se envía en una sin "
					+ "Concepto. ONLY_THIS: solo esta. THIS_AND_FUTURE: esta y las siguientes del Concepto.")
			@RequestParam(required = false) DeletionScope scope) {
		entries.delete(currentUser.id(), id, scope);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/entries/{id}/deletion-preview")
	@Operation(operationId = "getEntryDeletionPreview", summary = "Vista previa de la eliminación de una partida",
			description = "Informa, sin modificar nada, qué haría eliminar la partida: con cada alcance si es "
					+ "recurrente (`onlyThis`, `thisAndFuture`), o solo `removal` si no tiene Concepto. Para cada "
					+ "uno: si se puede, cuántas partidas se eliminarían y de qué mes a qué mes, qué pasa con el "
					+ "Concepto (sigue, termina en un mes o se elimina) y las partidas que lo impiden, con su mes y "
					+ "su motivo. Es la misma regla que aplica `DELETE /api/entries/{id}`.")
	@ApiResponse(responseCode = "200", description = "Vista previa de la eliminación.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período de la partida está cerrado (RN-09).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public EntryDeletionPreviewResponse deletionPreview(@PathVariable long id) {
		return EntryDeletionPreviewResponse.from(entries.deletionPreview(currentUser.id(), id));
	}
}
