package com.smartcoin.shared.isolation;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import org.mockito.Mockito;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RestController;

/** Registra un mock por cada tipo que piden los constructores de los {@code @RestController}. */
@TestConfiguration(proxyBeanMethods = false)
class ControllerDependencyMocks {

	@Bean
	static BeanDefinitionRegistryPostProcessor controllerDependencyMocks() {
		return new BeanDefinitionRegistryPostProcessor() {
			@Override
			public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
				List<Class<?>> needed = new ArrayList<>();
				for (String name : registry.getBeanDefinitionNames()) {
					String className = registry.getBeanDefinition(name).getBeanClassName();
					Class<?> type = load(className);
					if (type == null || !AnnotatedElementUtils.hasAnnotation(type, RestController.class)) {
						continue;
					}
					for (Constructor<?> constructor : type.getDeclaredConstructors()) {
						needed.addAll(List.of(constructor.getParameterTypes()));
					}
				}
				for (Class<?> type : needed) {
					if (!isDefined(registry, type)) {
						registry.registerBeanDefinition("mock" + type.getName(),
								mockDefinition(type));
					}
				}
			}

			@Override
			public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
			}
		};
	}

	private static <T> RootBeanDefinition mockDefinition(Class<T> type) {
		return new RootBeanDefinition(type, () -> Mockito.mock(type));
	}

	private static boolean isDefined(BeanDefinitionRegistry registry, Class<?> type) {
		for (String name : registry.getBeanDefinitionNames()) {
			if (type.getName().equals(registry.getBeanDefinition(name).getBeanClassName())) {
				return true;
			}
		}
		return false;
	}

	private static Class<?> load(String className) {
		if (className == null) {
			return null;
		}
		try {
			return Class.forName(className);
		}
		catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}
}
