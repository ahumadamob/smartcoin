package com.smartcoin.shared.isolation;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HU-06 (RN-01): todo método de búsqueda que declara un repositorio de datos del usuario recibe {@code userId}, para
 * que ninguna consulta pueda traer datos de otro usuario. Sin Spring ni base: solo reflexión sobre las interfaces.
 * Los métodos heredados de {@code JpaRepository} ({@code findById}, {@code findAll}, ...) no se pueden declarar
 * distintos; cada historia evita usarlos y declara su {@code findByIdAndUserId}.
 */
class RepositoryConventionTest {

	/** {@code User} es el propio usuario: se lo busca por email o por el id del token, no "de un usuario". */
	private static final Set<Class<?>> EXEMPT = Set.of(UserRepository.class);

	@Test
	void everyDeclaredRepositoryMethodReceivesUserId() throws Exception {
		List<Class<?>> repositories = repositories();
		assertThat(repositories).as("repositorios").isNotEmpty();

		List<String> violations = new ArrayList<>();
		for (Class<?> repository : repositories) {
			if (EXEMPT.contains(repository)) {
				continue;
			}
			for (Method method : repository.getDeclaredMethods()) {
				if (!receivesUserId(method)) {
					violations.add(repository.getSimpleName() + "." + method.getName());
				}
			}
		}

		assertThat(violations).as("métodos de repositorio sin userId").isEmpty();
	}

	private boolean receivesUserId(Method method) {
		for (Parameter parameter : method.getParameters()) {
			Param param = parameter.getAnnotation(Param.class);
			String name = param != null ? param.value() : parameter.getName();
			if ("userId".equals(name)) {
				return true;
			}
		}
		return false;
	}

	private List<Class<?>> repositories() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
				return definition.getMetadata().isInterface();
			}
		};
		scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
		List<Class<?>> found = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents("com.smartcoin")) {
			found.add(Class.forName(definition.getBeanClassName()));
		}
		return found;
	}
}
