package com.smartcoin.movement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * HU-21: códigos HTTP, forma de la respuesta y formato de errores de {@code PUT} y {@code DELETE
 * /api/movements/{id}}. Servicio simulado.
 */
@WebMvcTest(MovementItemController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + MovementItemControllerTest.SECRET)
class MovementItemControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final String VALID = """
			{"date":"2026-11-05","amount":30000.00,"accountId":44,"note":"Corregido"}""";

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

	private ResultActions update(String id, String json) throws Exception {
		return mvc.perform(put("/api/movements/" + id).header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions remove(String id) throws Exception {
		return mvc.perform(delete("/api/movements/" + id).header("Authorization", bearer()));
	}

	private static Registered updated() {
		BigDecimal budgeted = new BigDecimal("120000.00");
		EntryRow entry = new EntryRow(900L, null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, "Expensas", null, null,
				42L, "Banco", Currency.ARS, LocalDate.of(2026, 11, 10), null, null, budgeted,
				EntryAmounts.of(budgeted, StoredEntryStatus.PENDING, null, new BigDecimal("30000.00")), false,
				false);
		MovementRow movement = new MovementRow(1204L, 900L, LocalDate.of(2026, 11, 5), new BigDecimal("30000.00"),
				"Corregido", 44L, "Billetera", Currency.ARS);
		return new Registered(movement, entry);
	}

	@Test
	void updateRespondsOkWithTheMovementAndTheUpdatedEntry() throws Exception {
		when(movements.update(eq(USER_ID), eq(1204L), any(NewMovement.class))).thenReturn(updated());

		String body = update("1204", VALID)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.movement.id").value(1204))
				.andExpect(jsonPath("$.movement.entryId").value(900))
				.andExpect(jsonPath("$.movement.amount").value(30000.00))
				.andExpect(jsonPath("$.movement.accountName").value("Billetera"))
				.andExpect(jsonPath("$.entry.id").value(900))
				.andExpect(jsonPath("$.entry.actualAmount").value(30000.00))
				.andExpect(jsonPath("$.entry.pendingAmount").value(90000.00))
				.andExpect(jsonPath("$.entry.status").value("PARTIAL"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("\"amount\":30000.00").contains("\"pendingAmount\":90000.00");
	}

	@Test
	void updatePassesTheBodyAndTheUserOfTheTokenToTheService() throws Exception {
		when(movements.update(anyLong(), anyLong(), any())).thenReturn(updated());

		// No hay lugar para una partida ni un usuario en el cuerpo: se ignoran (RN-01, RN-22).
		update("1204", """
				{"userId":99,"entryId":5,"date":"2026-11-05","amount":30000.5,"accountId":44}""")
				.andExpect(status().isOk());

		ArgumentCaptor<NewMovement> values = ArgumentCaptor.forClass(NewMovement.class);
		verify(movements).update(eq(USER_ID), eq(1204L), values.capture());
		assertThat(values.getValue()).isEqualTo(new NewMovement(LocalDate.of(2026, 11, 5),
				new BigDecimal("30000.5"), 44L, null));
	}

	@Test
	void updateWithoutRequiredFieldsIsAValidationErrorWithTheErrorsPerField() throws Exception {
		update("1204", "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[?(@.field=='date')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='amount')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='accountId')]").exists());
		verifyNoInteractions(movements);
	}

	static Stream<String> malformedBodies() {
		return Stream.of(
				VALID.replace("30000.00", "0"),
				VALID.replace("30000.00", "-5"),
				VALID.replace("30000.00", "10.005"),
				VALID.replace("2026-11-05", "2026-02-30"),
				VALID.replace("Corregido", "x".repeat(201)));
	}

	@ParameterizedTest
	@MethodSource("malformedBodies")
	void updateWithAMalformedFieldIsAValidationError(String json) throws Exception {
		update("1204", json)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(movements);
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "1.5" })
	void anIdThatIsNotANumberIsAValidationError(String id) throws Exception {
		update(id, VALID).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		remove(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(movements);
	}

	@Test
	void updateOfAMovementThatIsNotTheUsersIsNotFound() throws Exception {
		when(movements.update(anyLong(), anyLong(), any()))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "El movimiento no existe."));

		update("1204", VALID)
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
	void updateRespondsConflictWithTheCodeAndTheDetail(ErrorCode code, String title) throws Exception {
		when(movements.update(anyLong(), anyLong(), any()))
				.thenThrow(new BusinessException(code, "Detalle en español."));

		update("1204", VALID)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(code.name()))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.title").value(title))
				.andExpect(jsonPath("$.detail").value("Detalle en español."));
	}

	@Test
	void updateWithAnAccountThatIsNotTheUsersIsAValidationErrorInItsField() throws Exception {
		when(movements.update(anyLong(), anyLong(), any()))
				.thenThrow(BusinessException.invalidField("accountId", "La cuenta no existe."));

		update("1204", VALID)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("accountId"));
	}

	@Test
	void updateWithoutATokenIsUnauthorized() throws Exception {
		mvc.perform(put("/api/movements/1204").contentType(MediaType.APPLICATION_JSON).content(VALID))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(movements);
	}

	@Test
	void deleteRespondsNoContentWithAnEmptyBody() throws Exception {
		remove("1204").andExpect(status().isNoContent()).andExpect(content().string(""));

		verify(movements).delete(USER_ID, 1204L);
	}

	@Test
	void deleteOfAMovementThatIsNotTheUsersIsNotFound() throws Exception {
		doThrow(new BusinessException(ErrorCode.NOT_FOUND, "El movimiento no existe."))
				.when(movements).delete(anyLong(), anyLong());

		remove("1204").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@ParameterizedTest
	@MethodSource("conflicts")
	void deleteRespondsConflictWithTheCodeAndTheDetail(ErrorCode code, String title) throws Exception {
		doThrow(new BusinessException(code, "Detalle en español.")).when(movements).delete(anyLong(), anyLong());

		remove("1204")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(code.name()))
				.andExpect(jsonPath("$.detail").value("Detalle en español."));
	}

	@Test
	void deleteWithoutATokenIsUnauthorized() throws Exception {
		mvc.perform(delete("/api/movements/1204")).andExpect(status().isUnauthorized());
		verifyNoInteractions(movements);
	}
}
