package com.github.pfichtner.jsipdialer;

import java.lang.reflect.Field;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.testcontainers.containers.GenericContainer;

class KamailioLogDumperExtension implements TestWatcher {

	@Override
	public void testFailed(@NonNull ExtensionContext context, Throwable cause) {
		findContainer(context).ifPresent(container -> {
			String logs = container.getLogs();
			if (!logs.isEmpty()) {
				System.err.println("=== KAMAILIO CONTAINER LOGS (test failed) ===");
				System.err.println(logs);
				System.err.println("=== END KAMAILIO LOGS ===");
				System.err.flush();
			}
		});
	}

	private Optional<GenericContainer<?>> findContainer(ExtensionContext context) {
		Class<?> testClass = context.getRequiredTestClass();
		while (testClass != null && testClass != Object.class) {
			for (Field field : testClass.getDeclaredFields()) {
				if (GenericContainer.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					try {
						Object instance = context.getTestInstance().orElse(null);
						Object value = field.get(instance);
						if (value instanceof GenericContainer) {
							return Optional.of((GenericContainer<?>) value);
						}
					} catch (IllegalAccessException e) {
						// skip
					}
				}
			}
			testClass = testClass.getSuperclass();
		}
		return Optional.empty();
	}
}
