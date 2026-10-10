package com.smartcoin.movement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.movement.service.MovementService;
import com.smartcoin.movement.service.MovementService.MovementRow;
import com.smartcoin.movement.service.MovementService.NewMovement;
import com.smartcoin.movement.service.MovementService.Registered;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.shared.security.CurrentUser;
import com.smartcoin.shared.security.SecurityConfig;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

/**
 * HU-19: códigos HTTP, forma de la respuesta y formato de errores de {@code POST} y {@code GET
 * /api/entries/{id}/movements}. Servicio simulado.
 */
@WebMvcTest(MovementController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + MovementControllerTest.SECRET)
class MovementControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final String VALID = """
			{"date":"2026-11-05","amount":70000.00,"accountId":42,"note":"Primera parte"}""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	MovementService movements;

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

	private ResultActions register(String entryId, String json) throws Exception {
		return mvc.perform(post("/api/entries/" + entryId + "/movements").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static Registered registered() {
		BigDecimal budgeted = new BigDecimal("120000.00");
		EntryRow entry = new EntryRow(900L, null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, "Expensas", null, null,
				42L, "Banco", Currency.ARS, LocalDate.of(2026, 11, 10), null, null, budgeted,
				EntryAmounts.of(budgeted, StoredEntryStatus.PENDING, null, new BigDecimal("70000.00")), false,
				false);
		MovementRow movement = new MovementRow(1204L, 900L, LocalDate.of(2026, 11, 5), new BigDecimal("70000.00"),
				"Primera parte", 42L, "Banco", Currency.ARS);
		return new Registered(movement, entry);
	}

	@Test
	void registerRespondsCreatedWithTheMovementAndTheUpdatedEntry() throws Exception {
		when(movements.register(eq(USER_ID), eq(900L), any(NewMovement.class))).thenReturn(registered());

		String body = register("900", VALID)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.movement.id").value(1204))
				.andExpect(jsonPath("$.movement.entryId").value(900))
				.andExpect(jsonPath("$.movement.date").value("2026-11-05"))
				.andExpect(jsonPath("$.movement.amount").value(70000.00))
				.andExpect(jsonPath("$.movement.note").value("Primera parte"))
				.andExpect(jsonPath("$.movement.accountId").value(42))
				.andExpect(jsonPath("$.movement.accountName").value("Banco"))
				.andExpect(jsonPath("$.movement.currency").value("ARS"))
				.andExpect(jsonPath("$.entry.id").value(900))
				.andExpect(jsonPath("$.entry.budgetedAmount").value(120000.00))
				.andExpect(jsonPath("$.entry.actualAmount").value(70000.00))
				.andExpect(jsonPath("$.entry.pendingAmount").value(50000.00))
				.andExpect(jsonPath("$.entry.status").value("PARTIAL"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		// Los montos viajan como número con 2 decimales (T-12).
		assertThat(body).contains("\"amount\":70000.00").contains("\"pendingAmount\":50000.00");
	}

	@Test
	void registerPassesTheBodyAndTheUserOfTheTokenToTheService() throws Exception {
		when(movements.register(anyLong(), anyLong(), any())).thenReturn(registered());

		// Un `userId` en el cuerpo no tiene dónde ir: se ignora y manda el del token (RN-01).
		register("900", """
				{"userId":99,"date":"2026-11-05","amount":70000.5,"accountId":42}""")
				.andExpect(status().isCreated());

		ArgumentCaptor<NewMovement> values = ArgumentCaptor.forClass(NewMovement.class);
		verify(movements).register(eq(USER_ID), eq(900L), values.capture());
		assertThat(values.getValue()).isEqualTo(new NewMovement(LocalDate.of(2026, 11, 5),
				new BigDecimal("70000.5"), 42L, null));
	}

	@Test
	void registerWithoutRequiredFieldsIsAValidationErrorWithTheErrorsPerField() throws Exception {
		register("900", "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[?(@.field=='date')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='amount')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='accountId')]").exists());
		verifyNoInteractions(movements);
	}

	static Stream<String> malformedBodies() {
		return Stream.of(
				VALID.replace("70000.00", "0"),
				VALID.replace("70000.00", "-5"),
				VALID.replace("70000.00", "10.005"),
				VALID.replace("70000.00", "\"abc\""),
				VALID.replace("2026-11-05", "2026-02-30"),
				VALID.replace("2026-11-05", "05/11/2026"),
				VALID.replace("Primera parte", "x".repeat(201)),
				VALID.replace("42", "\"abc\""));
	}

	@ParameterizedTest
	@MethodSource("malformedBodies")
	void registerWithAMalformedFieldIsAValidationError(String json) throws Exception {
		register("900", json)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(movements);
	}

	@Test
	void aNoteOfExactlyTwoHundredCharactersIsAccepted() throws Exception {
		when(movements.register(anyLong(), anyLong(), any())).thenReturn(registered());

		register("900", VALID.replace("Primera parte", "x".repeat(200))).andExpect(status().isCreated());
	}

	@Test
	void anAmountWithTwoDecimalsAndTheSmallestCentIsAccepted() throws Exception {
		when(movements.register(anyLong(), anyLong(), any())).thenReturn(registered());

		register("900", VALID.replace("70000.00", "0.01")).andExpect(status().isCreated());
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "1.5" })
	void registerWithAnEntryIdThatIsNotANumberIsAValidationError(String id) throws Exception {
		register(id, VALID).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(movements);
	}

	@Test
	void registerOnAnEntryThatIsNotTheUsersIsNotFound() throws Exception {
		when(movements.register(anyLong(), anyLong(), any()))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));

		register("900", VALID)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.status").value(404));
	}

	static Stream<Object[]> conflicts() {
		return Stream.of(
				new Object[] { ErrorCode.PERIOD_CLOSED, "Período cerrado" },
				new Object[] { ErrorCode.ENTRY_NOT_PENDING, "La partida no está pendiente" },
				new Object[] { ErrorCode.CURRENCY_MISMATCH, "Moneda distinta" },
				new Object[] { ErrorCode.DATE_OUT_OF_RANGE, "Fecha fuera de rango" });
	}

	@ParameterizedTest
	@MethodSource("conflicts")
	void theBusinessRulesRespondConflictWithTheirCodeAndTheDetail(ErrorCode code, String title) throws Exception {
		when(movements.register(anyLong(), anyLong(), any()))
				.thenThrow(new BusinessException(code, "Detalle en español."));

		register("900", VALID)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(code.name()))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.title").value(title))
				.andExpect(jsonPath("$.detail").value("Detalle en español."));
	}

	@Test
	void anAccountThatIsNotTheUsersIsAValidationErrorInItsField() throws Exception {
		when(movements.register(anyLong(), anyLong(), any()))
				.thenThrow(BusinessException.invalidField("accountId", "La cuenta no existe."));

		register("900", VALID)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("accountId"))
				.andExpect(jsonPath("$.errors[0].message").value("La cuenta no existe."));
	}

	@Test
	void registerWithoutATokenIsUnauthorized() throws Exception {
		mvc.perform(post("/api/entries/900/movements").contentType(MediaType.APPLICATION_JSON).content(VALID))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(movements);
	}

	@Test
	void listRespondsWithTheMovementsOfTheEntry() throws Exception {
		MovementRow second = new MovementRow(1205L, 900L, LocalDate.of(2026, 11, 12), new BigDecimal("50000.00"),
				null, 44L, "Billetera", Currency.ARS);
		when(movements.list(USER_ID, 900L)).thenReturn(List.of(registered().movement(), second));

		mvc.perform(get("/api/entries/900/movements").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].date").value("2026-11-05"))
				.andExpect(jsonPath("$[0].accountName").value("Banco"))
				.andExpect(jsonPath("$[1].amount").value(50000.00))
				.andExpect(jsonPath("$[1].note").doesNotExist())
				.andExpect(jsonPath("$[1].accountName").value("Billetera"));
	}

	@Test
	void listOfAnEntryWithoutMovementsIsAnEmptyArray() throws Exception {
		when(movements.list(USER_ID, 900L)).thenReturn(List.of());

		mvc.perform(get("/api/entries/900/movements").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void listOfAnEntryThatIsNotTheUsersIsNotFound() throws Exception {
		when(movements.list(anyLong(), anyLong()))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));

		mvc.perform(get("/api/entries/900/movements").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void listWithoutATokenIsUnauthorized() throws Exception {
		mvc.perform(get("/api/entries/900/movements")).andExpect(status().isUnauthorized());
		verifyNoInteractions(movements);
	}
}
