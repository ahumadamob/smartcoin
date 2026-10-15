package com.smartcoin.entry.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EstimationCalculator.BaseEntry;
import com.smartcoin.entry.domain.EstimationCalculator.Target;
import com.smartcoin.entry.domain.ManualEntriesPolicy;
import com.smartcoin.entry.domain.QuickSettleBlockReason;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.service.ConsolidationService;
import com.smartcoin.entry.service.QuickSettleService;
import com.smartcoin.entry.service.QuickSettleService.Preview;
import com.smartcoin.entry.service.QuickSettleService.Request;
import com.smartcoin.entry.service.QuickSettleService.Result;
import com.smartcoin.movement.service.MovementService.MovementRow;
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

/** HU-22: códigos HTTP, forma de la respuesta y formato de errores del pago rápido. Servicio simulado. */
@WebMvcTest(QuickSettleController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + QuickSettleControllerTest.SECRET)
class QuickSettleControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	QuickSettleService quickSettle;

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

	private ResultActions settle(String id, String json) throws Exception {
		var request = post("/api/entries/" + id + "/quick-settle").header("Authorization", bearer());
		if (json != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private static Result result() {
		BigDecimal budgeted = new BigDecimal("1800000.00");
		EntryRow row = new EntryRow(512L, 31L, EntryOrigin.RECURRING, EntryKind.INCOME, "Sueldo", null, null, 12L,
				"Banco", Currency.ARS, LocalDate.of(2026, 11, 25), null, null, budgeted,
				EntryAmounts.of(budgeted, StoredEntryStatus.CONSOLIDATED, budgeted, budgeted), false, false);
		MovementRow movement = new MovementRow(9001L, 512L, LocalDate.of(2026, 10, 15), new BigDecimal("1300000.00"),
				null, 12L, "Banco", Currency.ARS);
		return new Result(movement, new ConsolidationService.Result(row, budgeted, 3));
	}

	@Test
	void settleAnswersTheMovementTheConsolidatedEntryAndWhatPropagated() throws Exception {
		when(quickSettle.quickSettle(USER_ID, 512L, new Request(null, null, null))).thenReturn(result());

		settle("512", null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.movement.id").value(9001))
				.andExpect(jsonPath("$.movement.amount").value(1300000.00))
				.andExpect(jsonPath("$.movement.date").value("2026-10-15"))
				.andExpect(jsonPath("$.movement.accountId").value(12))
				.andExpect(jsonPath("$.entry.id").value(512))
				.andExpect(jsonPath("$.entry.status").value("CONSOLIDATED"))
				.andExpect(jsonPath("$.entry.pendingAmount").value(0))
				.andExpect(jsonPath("$.estimate").value(1800000.00))
				.andExpect(jsonPath("$.updatedEntries").value(3));
	}

	@Test
	void settlePassesDateAccountAndPolicyFromTheBody() throws Exception {
		when(quickSettle.quickSettle(anyLong(), anyLong(), any())).thenReturn(result());

		settle("512", "{\"date\":\"2026-10-03\",\"accountId\":44,\"manualEntriesPolicy\":\"KEEP\"}")
				.andExpect(status().isOk());

		ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
		verify(quickSettle).quickSettle(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(512L),
				captor.capture());
		assertThat(captor.getValue()).isEqualTo(new Request(LocalDate.of(2026, 10, 3), 44L, ManualEntriesPolicy.KEEP));
	}

	@Test
	void anEmptyBodyOrNullFieldsMeanDefaults() throws Exception {
		when(quickSettle.quickSettle(USER_ID, 512L, new Request(null, null, null))).thenReturn(result());

		settle("512", "{}").andExpect(status().isOk());
		settle("512", "{\"date\":null,\"accountId\":null,\"manualEntriesPolicy\":null}").andExpect(status().isOk());
	}

	@Test
	void anInvalidBodyIs400WithoutCallingTheService() throws Exception {
		settle("512", "{\"manualEntriesPolicy\":\"MAYBE\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		settle("512", "{\"date\":\"15/10/2026\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		settle("512", "{\"accountId\":\"abc\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(quickSettle);
	}

	@Test
	void businessErrorsKeepTheCommonFormatAndStatus() throws Exception {
		for (ErrorCode code : List.of(ErrorCode.PERIOD_CLOSED, ErrorCode.ENTRY_NOT_PENDING, ErrorCode.NOTHING_PENDING,
				ErrorCode.CURRENCY_MISMATCH, ErrorCode.DATE_OUT_OF_RANGE)) {
			org.mockito.Mockito.doThrow(new BusinessException(code, "detalle")).when(quickSettle)
					.quickSettle(anyLong(), anyLong(), any());
			settle("512", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code.name()))
					.andExpect(jsonPath("$.detail").value("detalle"));
		}
	}

	@Test
	void nothingPendingIs409WithItsOwnCode() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOTHING_PENDING, "No hay nada pendiente."))
				.when(quickSettle).quickSettle(anyLong(), anyLong(), any());

		settle("512", null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NOTHING_PENDING"))
				.andExpect(jsonPath("$.title").value("No hay nada pendiente"));
	}

	@Test
	void anAccountThatDoesNotExistIs400InItsField() throws Exception {
		org.mockito.Mockito.doThrow(BusinessException.invalidField("accountId", "La cuenta no existe."))
				.when(quickSettle).quickSettle(anyLong(), anyLong(), any());

		settle("512", "{\"accountId\":999}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("accountId"));
	}

	@Test
	void manualPolicyRequiredListsTheEditedEntries() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.MANUAL_POLICY_REQUIRED, "Elegí.",
				List.of(513L, 514L))).when(quickSettle).quickSettle(anyLong(), anyLong(), any());

		settle("512", null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MANUAL_POLICY_REQUIRED"))
				.andExpect(jsonPath("$.entries[0]").value(513))
				.andExpect(jsonPath("$.entries[1]").value(514));
	}

	@Test
	void anotherUsersEntryIs404() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."))
				.when(quickSettle).quickSettle(anyLong(), anyLong(), any());

		settle("999", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void previewAnswersTheAmountAndTheConsolidationThatWouldFollow() throws Exception {
		var consolidation = new ConsolidationService.Preview(512, YearMonth.of(2026, 10), true, null, null,
				new BigDecimal("1850000.00"), EstimationRule.LAST_VALUE, new BigDecimal("1850000.00"),
				new BigDecimal("1800000.00"), List.of(new BaseEntry(YearMonth.of(2026, 10), new BigDecimal("1850000.00"))),
				List.of(new Target(513, YearMonth.of(2026, 11), new BigDecimal("1800000.00"),
						new BigDecimal("1850000.00"), false, true)),
				false);
		when(quickSettle.preview(USER_ID, 512L))
				.thenReturn(new Preview(512, null, null, new BigDecimal("1350000.00"), consolidation));

		mvc.perform(get("/api/entries/512/quick-settle-preview").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryId").value(512))
				.andExpect(jsonPath("$.allowed").value(true))
				.andExpect(jsonPath("$.blockedBy").doesNotExist())
				.andExpect(jsonPath("$.amount").value(1350000.00))
				.andExpect(jsonPath("$.consolidation.actualAmount").value(1850000.00))
				.andExpect(jsonPath("$.consolidation.estimate").value(1850000.00))
				.andExpect(jsonPath("$.consolidation.targets[0].entryId").value(513))
				.andExpect(jsonPath("$.consolidation.targets[0].changes").value(true))
				.andExpect(jsonPath("$.consolidation.manualPolicyRequired").value(false));
	}

	@Test
	void previewOfABlockedEntryAnswers200WithTheReason() throws Exception {
		when(quickSettle.preview(USER_ID, 512L)).thenReturn(new Preview(512, QuickSettleBlockReason.NOTHING_PENDING,
				"No hay nada pendiente.", null, null));

		mvc.perform(get("/api/entries/512/quick-settle-preview").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.allowed").value(false))
				.andExpect(jsonPath("$.blockedBy").value("NOTHING_PENDING"))
				.andExpect(jsonPath("$.blockedDetail").value("No hay nada pendiente."))
				.andExpect(jsonPath("$.amount").doesNotExist())
				.andExpect(jsonPath("$.consolidation").doesNotExist());
	}

	@Test
	void previewOfAnotherUsersEntryIs404() throws Exception {
		when(quickSettle.preview(USER_ID, 999L))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));

		mvc.perform(get("/api/entries/999/quick-settle-preview").header("Authorization", bearer()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}
}
