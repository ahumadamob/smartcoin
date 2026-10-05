package com.smartcoin.shared.isolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HU-06, criterio 1: todo endpoint registrado en la aplicación exige credenciales. Los endpoints salen del registro
 * de Spring, no de una lista escrita a mano: uno nuevo queda cubierto sin tocar este test.
 */
class EndpointsRequireTokenTest extends AllControllersWebTest {

	/** Únicos endpoints que no exigen token. Agregar uno acá requiere una razón de negocio. */
	private static final Set<String> PUBLIC_PATHS = Set.of(
			"/api/auth/login"); // el login es lo que entrega el token

	/** Se autentican con {@code X-Admin-Key} en lugar de JWT; igual deben responder 401 sin la clave. */
	private static final String ADMIN_PREFIX = "/api/admin/";

	// Swagger UI y /v3/api-docs son públicos pero los publica springdoc, no figuran en el registro de controladores.

	@Test
	void everyRegisteredEndpointRespondsUnauthorizedWithoutCredentials() throws Exception {
		List<String> endpoints = endpoints();
		assertThat(endpoints).as("endpoints registrados").isNotEmpty();

		List<String> open = new ArrayList<>();
		for (String endpoint : endpoints) {
			String[] parts = endpoint.split(" ", 2);
			String path = parts[1].replaceAll("\\{[^}]*}", "1");
			if (PUBLIC_PATHS.contains(parts[1])) {
				continue;
			}
			int status = mvc.perform(request(HttpMethod.valueOf(parts[0]), path)).andReturn().getResponse().getStatus();
			if (status != 401) {
				open.add(endpoint + " -> " + status);
			}
		}
		assertThat(open).as("endpoints que no responden 401 sin token").isEmpty();
	}

	@Test
	void exceptionsExistAsRegisteredEndpoints() {
		// Evita que la lista de excepciones se llene de rutas que ya no existen.
		List<String> paths = endpoints().stream().map(e -> e.split(" ", 2)[1]).toList();
		assertThat(paths).containsAll(PUBLIC_PATHS);
	}

	@Test
	void adminEndpointsAreNotOpenWithAWrongKey() throws Exception {
		for (String endpoint : endpoints()) {
			String[] parts = endpoint.split(" ", 2);
			if (parts[1].startsWith(ADMIN_PREFIX)) {
				mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("X-Admin-Key", "incorrecta"))
						.andExpect(status().isUnauthorized());
			}
		}
	}

	private List<String> endpoints() {
		List<String> endpoints = new ArrayList<>();
		for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
			Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
			Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
			for (String pattern : patterns) {
				if (methods.isEmpty()) {
					endpoints.add("GET " + pattern);
				}
				methods.forEach(m -> endpoints.add(m.name() + " " + pattern));
			}
		}
		return endpoints;
	}
}
