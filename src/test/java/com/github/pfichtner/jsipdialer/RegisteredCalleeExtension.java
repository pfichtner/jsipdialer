package com.github.pfichtner.jsipdialer;

import java.io.IOException;
import java.lang.reflect.Parameter;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

class RegisteredCalleeExtension implements ParameterResolver, AfterEachCallback {

	private static final Namespace NAMESPACE = Namespace.create(RegisteredCalleeExtension.class);
	private static final String CALLEES_KEY = "callees";

	@Override
	public boolean supportsParameter(ParameterContext paramContext, ExtensionContext context) {
		Parameter parameter = paramContext.getParameter();
		return parameter.getType() == RegisteredCallee.class
				&& paramContext.findAnnotation(RegisterCallee.class).isPresent();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Object resolveParameter(ParameterContext paramContext, ExtensionContext context) {
		RegisterCallee annotation = paramContext.findAnnotation(RegisterCallee.class)
				.orElseThrow(() -> new IllegalStateException("@RegisterCallee not present on parameter"));

		int port = freePort();
		String user = annotation.user();

		RegisteredCallee callee = annotation.awaitRegistration()
				? RegisteredCallee.registerAndAwait(port, user, annotation.behavior())
				: RegisteredCallee.register(port, user, annotation.behavior());

		Store store = context.getStore(NAMESPACE);
		List<RegisteredCallee> callees = (List<RegisteredCallee>) store.getOrComputeIfAbsent(CALLEES_KEY,
				k -> new ArrayList<RegisteredCallee>(), List.class);
		callees.add(callee);
		return callee;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void afterEach(ExtensionContext context) {
		Store store = context.getStore(NAMESPACE);
		List<RegisteredCallee> callees = (List<RegisteredCallee>) store.get(CALLEES_KEY, List.class);
		if (callees == null) {
			return;
		}
		for (RegisteredCallee callee : callees) {
			safeClose(callee);
		}
		callees.clear();
	}

	private static void safeClose(RegisteredCallee callee) {
		try {
			callee.close();
		} catch (Exception e) {
			System.err.println("Failed to close RegisteredCallee: " + e.getMessage());
			System.err.flush();
		}
	}

	private static int freePort() {
		try (ServerSocket s = new ServerSocket(0)) {
			s.setReuseAddress(true);
			return s.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to allocate free port", e);
		}
	}
}
