package com.smartcoin.account.web;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.account.domain.Account;
import com.smartcoin.account.domain.AccountEditability;
import com.smartcoin.account.domain.AccountType;
import com.smartcoin.account.domain.AccountUsage;
import com.smartcoin.account.domain.AccountValues;
import com.smartcoin.account.service.AccountService;
import com.smartcoin.account.service.AccountService.AccountView;
import com.smartcoin.shared.domain.Currency;
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

/** HU-07: códigos HTTP, formato de errores y forma de la respuesta de /api/accounts. Servicio simulado. */
@WebMvcTest(AccountController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + AccountControllerTest.SECRET)
class AccountControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final long ACCOUNT_ID = 42;

	static final String VALID_BODY = """
			{"name": "Banco Nación", "type": "BANK", "currency": "ARS", "openingDate": "2026-08-01",
			 "initialBalance": -1500.5}""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AccountService accounts;

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

	private static AccountView view(AccountUsage usage) {
		Account account = new Account();
		ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
		account.setUserId(USER_ID);
		account.setName("Banco Nación");
		account.setType(AccountType.BANK);
		account.setCurrency(Currency.ARS);
		account.setOpeningDate(LocalDate.of(2026, 8, 1));
		account.setInitialBalance(new BigDecimal("-1500.50"));
		return new AccountView(account, AccountEditability.of(usage));
	}

	@Test
	void createRespondsCreatedWithTheResourceAndItsLocation() throws Exception {
		when(accounts.create(eq(USER_ID), any())).thenReturn(view(AccountUsage.unused()));

		send(post("/api/accounts"), VALID_BODY)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/accounts/42"))
				.andExpect(jsonPath("$.id").value(42))
				.andExpect(jsonPath("$.name").value("Banco Nación"))
				.andExpect(jsonPath("$.type").value("BANK"))
				.andExpect(jsonPath("$.currency").value("ARS"))
				.andExpect(jsonPath("$.openingDate").value("2026-08-01"))
				.andExpect(jsonPath("$.initialBalance").value(-1500.50))
				.andExpect(jsonPath("$.editability.currency.editable").value(true))
				.andExpect(jsonPath("$.editability.currency.reason").doesNotExist());

		ArgumentCaptor<AccountValues> sent = ArgumentCaptor.forClass(AccountValues.class);
		verify(accounts).create(eq(USER_ID), sent.capture());
		assertThat(sent.getValue().initialBalance()).isEqualByComparingTo("-1500.50");
		assertThat(sent.getValue().openingDate()).isEqualTo(LocalDate.of(2026, 8, 1));
	}

	@Test
	void theUserComesFromTheTokenNotFromTheBody() throws Exception {
		when(accounts.create(eq(USER_ID), any())).thenReturn(view(AccountUsage.unused()));

		send(post("/api/accounts"), VALID_BODY.replace("{", "{\"userId\": 999,"))
				.andExpect(status().isCreated());

		verify(accounts).create(eq(USER_ID), any());
	}

	@Test
	void listRespondsOkWithTheEditabilityOfEachAccount() throws Exception {
		when(accounts.list(USER_ID)).thenReturn(List.of(view(new AccountUsage(true, true, null))));

		mvc.perform(get("/api/accounts").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(42))
				.andExpect(jsonPath("$[0].editability.currency.editable").value(false))
				.andExpect(jsonPath("$[0].editability.currency.reason").isNotEmpty())
				.andExpect(jsonPath("$[0].editability.initialBalance.editable").value(false))
				.andExpect(jsonPath("$[0].editability.openingDate.editable").value(false))
				.andExpect(jsonPath("$[0].editability.openingDate.reason").isNotEmpty());
	}

	@Test
	void getRespondsNotFoundWithTheErrorFormat() throws Exception {
		when(accounts.get(USER_ID, 99)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "La cuenta no existe."));

		mvc.perform(get("/api/accounts/99").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("La cuenta no existe."));
	}

	@Test
	void updateRespondsOkAndTakesTheIdFromThePath() throws Exception {
		when(accounts.update(eq(USER_ID), eq(ACCOUNT_ID), any())).thenReturn(view(AccountUsage.unused()));

		send(put("/api/accounts/42"), VALID_BODY)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(42));
	}

	@Test
	void updateNotEditableRespondsConflictWithFieldNotEditable() throws Exception {
		when(accounts.update(eq(USER_ID), eq(ACCOUNT_ID), any())).thenThrow(
				new BusinessException(ErrorCode.FIELD_NOT_EDITABLE, "La moneda no se puede cambiar."));

		send(put("/api/accounts/42"), VALID_BODY)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("FIELD_NOT_EDITABLE"))
				.andExpect(jsonPath("$.detail").value("La moneda no se puede cambiar."));
	}

	@Test
	void nameTakenRespondsConflict() throws Exception {
		when(accounts.create(eq(USER_ID), any())).thenThrow(
				new BusinessException(ErrorCode.ACCOUNT_NAME_TAKEN, "Ya tenés una cuenta con ese nombre."));

		send(post("/api/accounts"), VALID_BODY)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NAME_TAKEN"));
	}

	@Test
	void openingDateOutOfRangeRespondsBadRequest() throws Exception {
		when(accounts.create(eq(USER_ID), any())).thenThrow(
				new BusinessException(ErrorCode.VALIDATION_ERROR, "La fecha de apertura no puede ser futura."));

		send(post("/api/accounts"), VALID_BODY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.detail").value("La fecha de apertura no puede ser futura."));
	}

	@Test
	void deleteRespondsNoContent() throws Exception {
		mvc.perform(delete("/api/accounts/42").header("Authorization", bearer()))
				.andExpect(status().isNoContent());

		verify(accounts).delete(USER_ID, ACCOUNT_ID);
	}

	@Test
	void deleteInUseRespondsConflict() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.ACCOUNT_IN_USE, "La cuenta está en uso."))
				.when(accounts).delete(USER_ID, ACCOUNT_ID);

		mvc.perform(delete("/api/accounts/42").header("Authorization", bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_IN_USE"));
	}

	@Test
	void invalidBodyRespondsValidationErrorWithAnErrorPerField() throws Exception {
		send(post("/api/accounts"), """
				{"name": "  ", "type": "BANK", "currency": "ARS", "openingDate": "2026-08-01",
				 "initialBalance": 10.123}""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.length()").value(2));

		verifyNoInteractions(accounts);
	}

	@Test
	void missingFieldsRespondValidationError() throws Exception {
		send(post("/api/accounts"), "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.length()").value(5));
	}

	@Test
	void unknownEnumOrMalformedDateRespondValidationError() throws Exception {
		send(post("/api/accounts"), VALID_BODY.replace("\"BANK\"", "\"CAJA\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		send(post("/api/accounts"), VALID_BODY.replace("2026-08-01", "01/08/2026"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void nameLongerThan100CharactersIsRejected() throws Exception {
		send(post("/api/accounts"), VALID_BODY.replace("Banco Nación", "x".repeat(101)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void everyEndpointWithoutTokenIsUnauthorized() throws Exception {
		mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/accounts/42")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
		mvc.perform(put("/api/accounts/42").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/accounts/42")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code")
				.value("UNAUTHORIZED"));

		verifyNoInteractions(accounts);
	}
}
