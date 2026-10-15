package com.smartcoin.transfer.web;

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

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.shared.security.CurrentUser;
import com.smartcoin.shared.security.SecurityConfig;
import com.smartcoin.transfer.service.TransferService;
import com.smartcoin.transfer.service.TransferService.NewTransfer;
import com.smartcoin.transfer.service.TransferService.TransferRow;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

/** HU-27: códigos HTTP, forma de la respuesta y formato de errores de {@code POST} y {@code GET /api/transfers}. */
@WebMvcTest(TransferController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + TransferControllerTest.SECRET)
class TransferControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final String VALID = """
			{"sourceAccountId":1,"targetAccountId":2,"date":"2026-10-15","sourceAmount":50000.00,
			"targetAmount":50000.00,"note":"Extracción"}""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	TransferService transfers;

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

	private ResultActions create(String json) throws Exception {
		return mvc.perform(post("/api/transfers").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static TransferRow row() {
		return new TransferRow(31L, LocalDate.of(2026, 10, 15), 1L, "Banco", Currency.ARS,
				new BigDecimal("50000.00"), 2L, "Efectivo", Currency.ARS, new BigDecimal("50000.00"), "Extracción");
	}

	@Test
	void createRespondsCreatedWithTheTransferAndItsAccounts() throws Exception {
		when(transfers.create(eq(USER_ID), any(NewTransfer.class))).thenReturn(row());

		String body = create(VALID)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(31))
				.andExpect(jsonPath("$.date").value("2026-10-15"))
				.andExpect(jsonPath("$.sourceAccountId").value(1))
				.andExpect(jsonPath("$.sourceAccountName").value("Banco"))
				.andExpect(jsonPath("$.sourceCurrency").value("ARS"))
				.andExpect(jsonPath("$.sourceAmount").value(50000.00))
				.andExpect(jsonPath("$.targetAccountId").value(2))
				.andExpect(jsonPath("$.targetCurrency").value("ARS"))
				.andExpect(jsonPath("$.targetAmount").value(50000.00))
				.andExpect(jsonPath("$.note").value("Extracción"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("\"sourceAmount\":50000.00").contains("\"targetAmount\":50000.00");
	}

	@Test
	void createPassesTheBodyAndTheUserOfTheTokenToTheService() throws Exception {
		when(transfers.create(anyLong(), any())).thenReturn(row());

		create("""
				{"userId":99,"sourceAccountId":1,"targetAccountId":2,"date":"2026-10-15","sourceAmount":10.5,
				"targetAmount":10.5}""").andExpect(status().isCreated());

		ArgumentCaptor<NewTransfer> values = ArgumentCaptor.forClass(NewTransfer.class);
		verify(transfers).create(eq(USER_ID), values.capture());
		assertThat(values.getValue()).isEqualTo(new NewTransfer(1L, 2L, LocalDate.of(2026, 10, 15),
				new BigDecimal("10.5"), new BigDecimal("10.5"), null));
	}

	@Test
	void createWithoutRequiredFieldsIsAValidationErrorWithTheErrorsPerField() throws Exception {
		create("{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[?(@.field=='sourceAccountId')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='targetAccountId')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='date')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='sourceAmount')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='targetAmount')]").exists());
		verifyNoInteractions(transfers);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-5", "10.001" })
	void anAmountThatIsNotPositiveOrHasMoreThanTwoDecimalsIsRejected(String amount) throws Exception {
		create("""
				{"sourceAccountId":1,"targetAccountId":2,"date":"2026-10-15","sourceAmount":%s,"targetAmount":10}"""
				.formatted(amount))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[?(@.field=='sourceAmount')]").exists());
		verifyNoInteractions(transfers);
	}

	@Test
	void aNoteOver200CharactersIsRejected() throws Exception {
		create("""
				{"sourceAccountId":1,"targetAccountId":2,"date":"2026-10-15","sourceAmount":10,"targetAmount":10,
				"note":"%s"}""".formatted("x".repeat(201)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='note')]").exists());
	}

	@Test
	void aBusinessErrorOnAFieldKeepsItsField() throws Exception {
		when(transfers.create(anyLong(), any())).thenThrow(BusinessException.invalidField("targetAccountId",
				"La cuenta de destino tiene que ser distinta de la de origen."));

		create(VALID)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("targetAccountId"));
	}

	@Test
	void amountsMismatchIsConflict() throws Exception {
		when(transfers.create(anyLong(), any())).thenThrow(
				new BusinessException(ErrorCode.TRANSFER_AMOUNTS_MISMATCH, "Los montos tienen que ser iguales."));

		create(VALID)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TRANSFER_AMOUNTS_MISMATCH"));
	}

	@Test
	void dateErrorsAreConflicts() throws Exception {
		when(transfers.create(anyLong(), any()))
				.thenThrow(new BusinessException(ErrorCode.DATE_OUT_OF_RANGE, "No puede ser posterior a hoy."))
				.thenThrow(new BusinessException(ErrorCode.PERIOD_CLOSED, "El período está cerrado."));

		create(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATE_OUT_OF_RANGE"));
		create(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
	}

	@Test
	void withoutTokenIsUnauthorized() throws Exception {
		mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/transfers")).andExpect(status().isUnauthorized());
		verifyNoInteractions(transfers);
	}

	@Test
	void listPassesTheRangeAndRespondsAnArray() throws Exception {
		when(transfers.list(eq(USER_ID), any(), any())).thenReturn(List.of(row()));

		mvc.perform(get("/api/transfers?from=2026-10-01&to=2026-10-15").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(31))
				.andExpect(jsonPath("$[0].sourceAccountName").value("Banco"))
				.andExpect(jsonPath("$[0].targetAccountName").value("Efectivo"));

		verify(transfers).list(USER_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
	}

	@Test
	void listWithoutRangePassesNulls() throws Exception {
		when(transfers.list(eq(USER_ID), any(), any())).thenReturn(List.of());

		mvc.perform(get("/api/transfers").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());

		verify(transfers).list(USER_ID, null, null);
	}

	@Test
	void anInvertedRangeIsABadRequestOnFrom() throws Exception {
		when(transfers.list(anyLong(), any(), any())).thenThrow(
				BusinessException.invalidField("from", "La fecha desde no puede ser posterior a la fecha hasta."));

		mvc.perform(get("/api/transfers?from=2026-10-10&to=2026-10-01").header("Authorization", bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("from"));
	}

	@Test
	void aMalformedDateIsABadRequest() throws Exception {
		mvc.perform(get("/api/transfers?from=ayer").header("Authorization", bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(transfers);
	}
}
