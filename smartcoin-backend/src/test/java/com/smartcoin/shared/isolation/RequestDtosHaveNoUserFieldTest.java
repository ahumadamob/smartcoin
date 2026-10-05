package com.smartcoin.shared.isolation;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HU-06, criterio 4 (RN-01): el usuario sale siempre del token. Ningún tipo usado como cuerpo de un pedido (ni los
 * que contiene) puede tener un campo {@code userId} o {@code user}.
 */
class RequestDtosHaveNoUserFieldTest extends AllControllersWebTest {

	private static final Set<String> FORBIDDEN = Set.of("userid", "user");

	@Test
	void noRequestBodyTypeCarriesAUser() {
		List<Class<?>> bodies = requestBodyTypes();
		assertThat(bodies).as("tipos de cuerpo de pedido").isNotEmpty();

		List<String> violations = new ArrayList<>();
		Set<Class<?>> visited = new HashSet<>();
		bodies.forEach(type -> inspect(type, visited, violations));

		assertThat(violations).as("campos de usuario en DTOs de entrada").isEmpty();
	}

	private List<Class<?>> requestBodyTypes() {
		List<Class<?>> types = new ArrayList<>();
		for (HandlerMethod method : handlerMapping.getHandlerMethods().values()) {
			for (MethodParameter parameter : method.getMethodParameters()) {
				if (parameter.hasParameterAnnotation(RequestBody.class)) {
					types.add(parameter.getParameterType());
					collectGenerics(parameter.getGenericParameterType(), types);
				}
			}
		}
		return types;
	}

	private void inspect(Class<?> type, Set<Class<?>> visited, List<String> violations) {
		if (type.isPrimitive() || type.getName().startsWith("java.") || !visited.add(type)) {
			return;
		}
		List<Type> fieldTypes = new ArrayList<>();
		if (type.isRecord()) {
			for (RecordComponent component : type.getRecordComponents()) {
				check(type, component.getName(), violations);
				fieldTypes.add(component.getGenericType());
			}
		}
		else {
			for (Field field : type.getDeclaredFields()) {
				check(type, field.getName(), violations);
				fieldTypes.add(field.getGenericType());
			}
		}
		List<Class<?>> nested = new ArrayList<>();
		fieldTypes.forEach(t -> {
			if (t instanceof Class<?> c) {
				nested.add(c);
			}
			collectGenerics(t, nested);
		});
		nested.forEach(n -> inspect(n, visited, violations));
	}

	private void check(Class<?> owner, String name, List<String> violations) {
		if (FORBIDDEN.contains(name.toLowerCase())) {
			violations.add(owner.getName() + "." + name);
		}
	}

	private void collectGenerics(Type type, List<Class<?>> into) {
		if (type instanceof ParameterizedType parameterized) {
			for (Type argument : parameterized.getActualTypeArguments()) {
				if (argument instanceof Class<?> c) {
					into.add(c);
				}
				collectGenerics(argument, into);
			}
		}
	}
}
