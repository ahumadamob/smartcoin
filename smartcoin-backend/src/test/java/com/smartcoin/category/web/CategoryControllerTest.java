package com.smartcoin.category.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.shared.security.CurrentUser;
import com.smartcoin.shared.security.SecurityConfig;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.YearMonth;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.service.CategoryService;
import java.util.List;

/** HU-09: códigos HTTP, formato de errores y forma de la respuesta de /api/categories. Servicio simulado. */
@WebMvcTest(CategoryController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + CategoryControllerTest.SECRET)
class CategoryControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final long CATEGORY_ID = 42;

	static final String VALID_BODY = "{\"name\": \"Impuestos\"}";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CategoryService categories;

	@MockitoBean
	UserRepository users;

	@BeforeEach
	void existingEnabledUser() {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		user.setEnabled(true);
		user.setCredentialsVersion(1);
		user.setStartPeriod(YearMonth.of(2026, 8));
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
	}

	private static String bearer() {
		var key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var claims = JwtClaimsSet.builder().subject(String.valueOf(USER_ID)).claim("cv", 1)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		return "Bearer " + new NimbusJwtEncoder(new ImmutableSecret<>(key))
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static Category category(long id, String name) {
		Category category = new Category();
		ReflectionTestUtils.setField(category, "id", id);
		category.setUserId(USER_ID);
		category.setName(name);
		return category;
	}

	@Test
	void createRespondsCreatedWithTheResourceAndItsLocation() throws Exception {
		when(categories.create(USER_ID, "Impuestos")).thenReturn(category(CATEGORY_ID, "Impuestos"));

		send(post("/api/categories"), VALID_BODY)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/categories/42"))
				.andExpect(jsonPath("$.id").value(42))
				.andExpect(jsonPath("$.name").value("Impuestos"));
	}

	@Test
	void theUserComesFromTheTokenNotFromTheBody() throws Exception {
		when(categories.create(eq(USER_ID), any())).thenReturn(category(CATEGORY_ID, "Impuestos"));

		send(post("/api/categories"), "{\"userId\": 999, \"name\": \"Impuestos\"}")
				.andExpect(status().isCreated());

		verify(categories).create(eq(USER_ID), any());
	}

	@Test
	void listRespondsWithTheCategoriesInTheOrderOfTheService() throws Exception {
		when(categories.list(USER_ID)).thenReturn(List.of(category(1, "Hogar"), category(2, "Servicios")));

		mvc.perform(get("/api/categories").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("Hogar"))
				.andExpect(jsonPath("$[1].id").value(2));
	}

	@Test
	void listWithoutCategoriesRespondsAnEmptyList() throws Exception {
		when(categories.list(USER_ID)).thenReturn(List.of());

		mvc.perform(get("/api/categories").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void updateRespondsOkAndTakesTheIdFromThePath() throws Exception {
		when(categories.update(USER_ID, CATEGORY_ID, "Impuestos")).thenReturn(category(CATEGORY_ID, "Impuestos"));

		send(put("/api/categories/42"), VALID_BODY)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(42))
				.andExpect(jsonPath("$.name").value("Impuestos"));
	}

	@Test
	void updateOfAnotherUsersCategoryRespondsNotFoundWithTheErrorFormat() throws Exception {
		when(categories.update(eq(USER_ID), eq(99L), any()))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "La categoría no existe."));

		send(put("/api/categories/99"), VALID_BODY)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("La categoría no existe."));
	}

	@Test
	void nameTakenRespondsConflictOnCreateAndOnRename() throws Exception {
		BusinessException taken = new BusinessException(ErrorCode.CATEGORY_NAME_TAKEN,
				"Ya tenés una categoría con ese nombre.");
		when(categories.create(eq(USER_ID), any())).thenThrow(taken);
		when(categories.update(eq(USER_ID), eq(CATEGORY_ID), any())).thenThrow(taken);

		send(post("/api/categories"), VALID_BODY)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"))
				.andExpect(jsonPath("$.detail").value("Ya tenés una categoría con ese nombre."));
		send(put("/api/categories/42"), VALID_BODY)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
	}

	@Test
	void deleteRespondsNoContent() throws Exception {
		mvc.perform(delete("/api/categories/42").header("Authorization", bearer()))
				.andExpect(status().isNoContent());

		verify(categories).delete(USER_ID, CATEGORY_ID);
	}

	@Test
	void deleteInUseRespondsConflict() throws Exception {
		doThrow(new BusinessException(ErrorCode.CATEGORY_IN_USE, "La categoría está en uso."))
				.when(categories).delete(USER_ID, CATEGORY_ID);

		mvc.perform(delete("/api/categories/42").header("Authorization", bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
	}

	@Test
	void deleteOfAnotherUsersCategoryRespondsNotFound() throws Exception {
		doThrow(new BusinessException(ErrorCode.NOT_FOUND, "La categoría no existe."))
				.when(categories).delete(USER_ID, 99);

		mvc.perform(delete("/api/categories/99").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void blankMissingOrTooLongNamesRespondValidationErrorWithAnErrorPerField() throws Exception {
		for (String body : List.of("{\"name\": \"   \"}", "{}", "{\"name\": \"" + "x".repeat(61) + "\"}")) {
			send(post("/api/categories"), body)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
					.andExpect(jsonPath("$.errors.length()").value(1))
					.andExpect(jsonPath("$.errors[0].field").value("name"));
		}

		verifyNoInteractions(categories);
	}

	@Test
	void aNameOfExactly60CharactersIsAccepted() throws Exception {
		when(categories.create(eq(USER_ID), any())).thenReturn(category(CATEGORY_ID, "x".repeat(60)));

		send(post("/api/categories"), "{\"name\": \"" + "x".repeat(60) + "\"}")
				.andExpect(status().isCreated());
	}

	@Test
	void everyEndpointWithoutTokenIsUnauthorized() throws Exception {
		mvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
		mvc.perform(put("/api/categories/42").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/categories/42")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(categories);
	}
}
