package com.smartcoin.user.web;

import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.shared.security.SecurityConfig;
import com.smartcoin.user.service.UserService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con {@code APP_ADMIN_KEY} vacía (así llega cuando la variable no existe) el endpoint rechaza siempre. */
@WebMvcTest(AdminUserController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = {
		"app.security.jwt-secret=0123456789abcdef0123456789abcdef",
		"app.security.admin-key=" })
class AdminUserControllerKeyNotConfiguredTest {

	private static final String BODY = "{\"email\": \"persona@ejemplo.com\", \"password\": \"clave-larga-123\"}";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	UserService users;

	@Test
	void anyKeyIsUnauthorized() throws Exception {
		mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(BODY)
				.header("X-Admin-Key", "cualquier-clave"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(users);
	}

	@Test
	void emptyKeyIsNotAMatchEither() throws Exception {
		mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(BODY)
				.header("X-Admin-Key", ""))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(users);
	}

	@Test
	void withoutHeaderIsUnauthorized() throws Exception {
		mvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(users);
	}
}
