package com.smartcoin.shared.error;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import com.smartcoin.shared.security.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerTest.ProbeController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ProbeController.class })
@TestPropertySource(properties = "app.security.jwt-secret=0123456789abcdef0123456789abcdef")
class GlobalExceptionHandlerTest {

	@Autowired
	MockMvc mvc;

	@RestController
	static class ProbeController {

		record Body(@NotBlank String name, @Min(1) int amount) {
		}

		record Dates(LocalDate date, YearMonth period) {
		}

		@GetMapping("/probe/business")
		void business() {
			throw new BusinessException(ErrorCode.PERIOD_CLOSED, "El período 2026-10 está cerrado y no admite cambios.");
		}

		@GetMapping("/probe/business-entries")
		void businessWithEntries() {
			throw new BusinessException(ErrorCode.UNRESOLVED_PENDING_ENTRIES, "Hay partidas sin resolver.", List.of(3L, 7L));
		}

		@PostMapping("/probe/body")
		void body(@Valid @RequestBody Body body) {
		}

		@GetMapping("/probe/period/{period}")
		Dates period(@PathVariable YearMonth period) {
			return new Dates(LocalDate.of(2026, 11, 25), period);
		}

		@GetMapping("/probe/param")
		void param(@RequestParam @Min(1) int count) {
		}

		@GetMapping("/probe/boom")
		void boom() {
			throw new IllegalStateException("detalle interno que no debe filtrarse");
		}
	}

	@Test
	void businessExceptionUsesCodeStatusAndSpanishDetail() throws Exception {
		mvc.perform(get("/probe/business").with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Período cerrado"))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("El período 2026-10 está cerrado y no admite cambios."))
				.andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
				.andExpect(jsonPath("$.entries").doesNotExist());
	}

	@Test
	void businessExceptionIncludesEntries() throws Exception {
		mvc.perform(get("/probe/business-entries").with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("UNRESOLVED_PENDING_ENTRIES"))
				.andExpect(jsonPath("$.entries[0]").value(3))
				.andExpect(jsonPath("$.entries[1]").value(7));
	}

	@Test
	void beanValidationErrorsListFields() throws Exception {
		mvc.perform(post("/probe/body").with(jwt()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\",\"amount\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field == 'amount')]").exists());
	}

	@Test
	void unreadableJsonIsValidationError() throws Exception {
		mvc.perform(post("/probe/body").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{no es json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void constraintOnRequestParamIsValidationError() throws Exception {
		mvc.perform(get("/probe/param").param("count", "0").with(jwt()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("count"));
	}

	@Test
	void missingRequestParamIsValidationError() throws Exception {
		mvc.perform(get("/probe/param").with(jwt()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void invalidPeriodIsValidationError() throws Exception {
		mvc.perform(get("/probe/period/2026-13").with(jwt()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void periodAndDatesUseIsoFormats() throws Exception {
		mvc.perform(get("/probe/period/2026-10").with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.period").value("2026-10"))
				.andExpect(jsonPath("$.date").value("2026-11-25"));
	}

	@Test
	void unknownRouteIsNotFound() throws Exception {
		mvc.perform(get("/probe/inexistente").with(jwt()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void wrongMethodIsMethodNotAllowed() throws Exception {
		mvc.perform(delete("/probe/business").with(jwt()))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void nonJsonBodyIsUnsupportedMediaType() throws Exception {
		mvc.perform(post("/probe/body").with(jwt()).contentType(MediaType.TEXT_PLAIN).content("hola"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void unexpectedErrorHidesInternalDetail() throws Exception {
		mvc.perform(get("/probe/boom").with(jwt()))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.detail").value("Ocurrió un error inesperado."))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("detalle"))));
	}
}
