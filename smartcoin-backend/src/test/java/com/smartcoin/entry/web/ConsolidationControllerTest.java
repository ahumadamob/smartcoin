package com.smartcoin.entry.web;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.entry.domain.ConsolidationBlockReason;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EstimationCalculator.Target;
import com.smartcoin.entry.domain.ManualEntriesPolicy;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.service.ConsolidationService;
import com.smartcoin.entry.domain.EstimationCalculator.BaseEntry;
import com.smartcoin.entry.service.ConsolidationService.Preview;
import com.smartcoin.entry.service.ConsolidationService.Result;
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

/** HU-23: códigos HTTP, forma de la respuesta y formato de errores de la consolidación. Servicio simulado. */
@WebMvcTest(ConsolidationController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + ConsolidationControllerTest.SECRET)
class ConsolidationControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ConsolidationService consolidation;

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

	private ResultActions consolidate(String id, String json) throws Exception {
		var request = post("/api/entries/" + id + "/consolidate").header("Authorization", bearer());
		if (json != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private static EntryRow consolidatedRow() {
		BigDecimal budgeted = new BigDecimal("1800000.00");
		return new EntryRow(512L, 31L, EntryOrigin.RECURRING, EntryKind.INCOME, "Sueldo", null, null, 12L, "Banco",
				Currency.ARS, LocalDate.of(2026, 11, 25), null, null, budgeted,
				EntryAmounts.of(budgeted, StoredEntryStatus.CONSOLIDATED, new BigDecimal("1850000.00"),
						new BigDecimal("1850000.00")),
				false, false);
	}

	@Test
	void previewAnswersRealEstimateCurrentAmountAndTargets() throws Exception {
		Preview preview = new Preview(512, YearMonth.of(2026, 12), true, null, null, new BigDecimal("1850000.00"),
				EstimationRule.LAST_VALUE, new BigDecimal("1850000.00"), new BigDecimal("1800000.00"),
				List.of(new BaseEntry(YearMonth.of(2026, 12), new BigDecimal("1850000.00"))),
				List.of(new Target(513, YearMonth.of(2027, 1), new BigDecimal("1800000.00"),
						new BigDecimal("1850000.00"), false, true),
						new Target(514, YearMonth.of(2027, 2), new BigDecimal("1850000.00"),
								new BigDecimal("1850000.00"), true, false)),
				true);
		when(consolidation.preview(USER_ID, 512L)).thenReturn(preview);

		mvc.perform(get("/api/entries/512/consolidation-preview").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryId").value(512))
				.andExpect(jsonPath("$.period").value("2026-12"))
				.andExpect(jsonPath("$.allowed").value(true))
				.andExpect(jsonPath("$.blockedBy").doesNotExist())
				.andExpect(jsonPath("$.actualAmount").value(1850000.00))
				.andExpect(jsonPath("$.estimate").value(1850000.00))
				.andExpect(jsonPath("$.currentAmount").value(1800000.00))
				.andExpect(jsonPath("$.estimationRule").value("LAST_VALUE"))
				.andExpect(jsonPath("$.manualPolicyRequired").value(true))
				.andExpect(jsonPath("$.estimateBasis.length()").value(1))
				.andExpect(jsonPath("$.estimateBasis[0].period").value("2026-12"))
				.andExpect(jsonPath("$.estimateBasis[0].amount").value(1850000.00))
				.andExpect(jsonPath("$.targets[0].entryId").value(513))
				.andExpect(jsonPath("$.targets[0].period").value("2027-01"))
				.andExpect(jsonPath("$.targets[0].currentBudgetedAmount").value(1800000.00))
				.andExpect(jsonPath("$.targets[0].newBudgetedAmount").value(1850000.00))
				.andExpect(jsonPath("$.targets[0].manual").value(false))
				.andExpect(jsonPath("$.targets[0].changes").value(true))
				.andExpect(jsonPath("$.targets[1].manual").value(true))
				.andExpect(jsonPath("$.targets[1].changes").value(false));
	}

	@Test
	void previewOfAnEntryThatCannotBeConsolidatedAnswers200WithTheReason() throws Exception {
		Preview preview = new Preview(512, YearMonth.of(2026, 12), true,
				ConsolidationBlockReason.CONSOLIDATION_REQUIRES_MOVEMENT, "No tiene movimientos.",
				new BigDecimal("0.00"), null, null, null, List.of(), List.of(), false);
		when(consolidation.preview(USER_ID, 512L)).thenReturn(preview);

		mvc.perform(get("/api/entries/512/consolidation-preview").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.allowed").value(false))
				.andExpect(jsonPath("$.blockedBy").value("CONSOLIDATION_REQUIRES_MOVEMENT"))
				.andExpect(jsonPath("$.blockedDetail").value("No tiene movimientos."))
				.andExpect(jsonPath("$.estimate").doesNotExist())
				.andExpect(jsonPath("$.estimateBasis.length()").value(0))
				.andExpect(jsonPath("$.targets.length()").value(0));
	}

	@Test
	void previewOfAnotherUsersEntryIs404() throws Exception {
		when(consolidation.preview(USER_ID, 999L))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));

		mvc.perform(get("/api/entries/999/consolidation-preview").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void consolidateAnswersTheEntryTheEstimateAndHowManyChanged() throws Exception {
		when(consolidation.consolidate(USER_ID, 512L, null))
				.thenReturn(new Result(consolidatedRow(), new BigDecimal("1850000.00"), 24));

		consolidate("512", null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entry.id").value(512))
				.andExpect(jsonPath("$.entry.status").value("CONSOLIDATED"))
				.andExpect(jsonPath("$.entry.pendingAmount").value(0))
				.andExpect(jsonPath("$.entry.forecastAmount").value(1850000.00))
				.andExpect(jsonPath("$.estimate").value(1850000.00))
				.andExpect(jsonPath("$.updatedEntries").value(24));
	}

	@Test
	void consolidatePassesThePolicyFromTheBody() throws Exception {
		when(consolidation.consolidate(USER_ID, 512L, ManualEntriesPolicy.OVERWRITE))
				.thenReturn(new Result(consolidatedRow(), new BigDecimal("1850000.00"), 25));

		consolidate("512", "{\"manualEntriesPolicy\":\"OVERWRITE\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.updatedEntries").value(25));
		verify(consolidation).consolidate(USER_ID, 512L, ManualEntriesPolicy.OVERWRITE);
	}

	@Test
	void consolidateAnEmptyBodyOrNullPolicyMeansNoPolicy() throws Exception {
		when(consolidation.consolidate(eq(USER_ID), anyLong(), isNull()))
				.thenReturn(new Result(consolidatedRow(), null, 0));

		consolidate("512", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.estimate").doesNotExist());
		consolidate("512", "{\"manualEntriesPolicy\":null}").andExpect(status().isOk());
	}

	@Test
	void anInvalidPolicyIs400WithoutCallingTheService() throws Exception {
		consolidate("512", "{\"manualEntriesPolicy\":\"MAYBE\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(consolidation);
	}

	@Test
	void businessErrorsKeepTheCommonFormatAndStatus() throws Exception {
		for (ErrorCode code : List.of(ErrorCode.PERIOD_CLOSED, ErrorCode.ENTRY_NOT_PENDING,
				ErrorCode.CONSOLIDATION_REQUIRES_MOVEMENT)) {
			org.mockito.Mockito.doThrow(new BusinessException(code, "detalle")).when(consolidation)
					.consolidate(anyLong(), anyLong(), isNull());
			consolidate("512", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code.name()))
					.andExpect(jsonPath("$.detail").value("detalle"));
		}
	}

	@Test
	void manualPolicyRequiredListsTheEditedEntries() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.MANUAL_POLICY_REQUIRED, "Elegí.",
				List.of(513L, 514L))).when(consolidation).consolidate(anyLong(), anyLong(), isNull());

		consolidate("512", null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MANUAL_POLICY_REQUIRED"))
				.andExpect(jsonPath("$.entries[0]").value(513))
				.andExpect(jsonPath("$.entries[1]").value(514));
	}

	@Test
	void anotherUsersEntryIs404() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."))
				.when(consolidation).consolidate(anyLong(), anyLong(), isNull());

		consolidate("999", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}
}
