package com.smartcoin.shared.isolation;


import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.shared.security.SecurityConfig;
import com.smartcoin.user.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Base de los tests de aislamiento (HU-06, RN-01): levanta la capa web con <b>todos</b> los controladores de la
 * aplicación y le agrega un mock por cada dependencia de sus constructores, así un controlador nuevo queda cubierto
 * sin tocar estos tests. No usa la base de datos.
 */
@WebMvcTest
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, ControllerDependencyMocks.class })
@TestPropertySource(properties = {
		"app.security.jwt-secret=0123456789abcdef0123456789abcdef",
		"app.security.admin-key=" + AllControllersWebTest.ADMIN_KEY })
abstract class AllControllersWebTest {

	static final String ADMIN_KEY = "clave-de-administracion-de-prueba";

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected RequestMappingHandlerMapping handlerMapping;

	// Lo necesita la cadena de seguridad, no los controladores.
	@MockitoBean
	UserRepository users;
}
