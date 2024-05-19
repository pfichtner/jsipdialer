package com.github.pfichtner.jsipdialer;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;
import static org.approvaltests.Approvals.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class SipClientMainTest {

	private final class SipClientMainSpy extends SipClientMain {

		private final Connection theConnection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class[] { Connection.class }, (InvocationHandler) (proxy, method, args) -> null);

		private String server;
		private int port;
		private SipConfig sipConfig;
		private Call call;
		private Connection connection;

		@Override
		protected Connection makeConnection(String server, int port) throws Exception {
			this.server = server;
			this.port = port;
			return theConnection;
		}

		@Override
		protected void execCall(SipConfig sipConfig, Call call, Connection connection) throws Exception {
			this.sipConfig = sipConfig;
			this.call = call;
			this.connection = connection;
		}

	}

	String[] required = new String[] { "sipServerAddress", "destinationNumber", "callerName" };

	SipClientMainSpy sipClientMainSpy = new SipClientMainSpy();
	ByteArrayOutputStream stdout = new ByteArrayOutputStream();
	ByteArrayOutputStream stderr = new ByteArrayOutputStream();

	@BeforeEach
	void setup() {
		System.setOut(new PrintStream(stdout));
		System.setErr(new PrintStream(stderr));
	}

	@Test
	void noArgsAtAll() throws Exception {
		callMain();
		assertThat(stderr.toString()).contains("Missing required options: " + stream(required).collect(joining(", ")));
		verifyStdoutAndStderr();
	}

	@Test
	void requiredArgumentsSetButUserAndPasswordMissing() throws Exception {
		callMain(addValues(required));
		verifyStdoutAndStderr();
	}

	@Test
	void requiredArgumentsAndUserSetButPasswordMissing() throws Exception {
		callMain(addValues(and(required, "sipUsername")));
		verifyStdoutAndStderr();
	}

	@Test
	void allRequiredParametersSet() throws Exception {
		callMain(addValues(and(required, "sipUsername", "sipPassword")));
		assertSoftly(c -> {
			c.assertThat(sipClientMainSpy.server).isEqualTo("1");
			c.assertThat(sipClientMainSpy.port).isEqualTo(SipClientMain.DEFAULT_SIPPORT);
			c.assertThat(sipClientMainSpy.call.getDestinationNumber()).isEqualTo("2");
			c.assertThat(sipClientMainSpy.call.getCallerName()).isEqualTo("3");
			c.assertThat(sipClientMainSpy.sipConfig.getUsername()).isEqualTo("4");
			c.assertThat(sipClientMainSpy.sipConfig.getPassword()).isEqualTo("5");
			c.assertThat(sipClientMainSpy.connection).isSameAs(sipClientMainSpy.theConnection);
			c.assertThat(sipClientMainSpy.call.getTimeout()).isEqualTo(SipClientMain.DEFAULT_TIMEOUT);
		});
	}

	@Test
	@SetEnvironmentVariable(key = "SIP_USERNAME", value = "4viaEnv")
	@SetEnvironmentVariable(key = "SIP_PASSWORD", value = "5viaEnv")
	void allRequiredParametersSetWhereUsernameAndPasswordAreSetViaEnvVars() throws Exception {
		callMain(addValues(required));
		assertSoftly(c -> {
			c.assertThat(sipClientMainSpy.server).isEqualTo("1");
			c.assertThat(sipClientMainSpy.port).isEqualTo(SipClientMain.DEFAULT_SIPPORT);
			c.assertThat(sipClientMainSpy.call.getDestinationNumber()).isEqualTo("2");
			c.assertThat(sipClientMainSpy.call.getCallerName()).isEqualTo("3");
			c.assertThat(sipClientMainSpy.sipConfig.getUsername()).isEqualTo("4viaEnv");
			c.assertThat(sipClientMainSpy.sipConfig.getPassword()).isEqualTo("5viaEnv");
			c.assertThat(sipClientMainSpy.connection).isSameAs(sipClientMainSpy.theConnection);
			c.assertThat(sipClientMainSpy.call.getTimeout()).isEqualTo(SipClientMain.DEFAULT_TIMEOUT);
		});
	}

	@Test
	void canSetOptionalValues() throws Exception {
		callMain(addValues(and(required, "sipUsername", "sipPassword", "sipServerPort", "timeout")));
		assertSoftly(c -> {
			c.assertThat(sipClientMainSpy.port).isEqualTo(6);
			c.assertThat(sipClientMainSpy.call.getTimeout()).isEqualTo(7);
		});
	}

	@Test
	void timeoutSetAsWell() throws Exception {
		callMain(addValues(and(required, "sipUsername", "sipPassword", "timeout")));
		assertThat(sipClientMainSpy.call.getTimeout()).isEqualTo(6);
	}

	private String[] and(String[] strings, String... others) {
		return concat(Arrays.stream(strings), Arrays.stream(others)).toArray(String[]::new);
	}

	private String[] addValues(String... in) {
		var value = new AtomicInteger();
		return Arrays.stream(in).map(p -> List.of("-" + p, String.valueOf(value.incrementAndGet())))
				.flatMap(Collection::stream).toArray(String[]::new);
	}

	private void verifyStdoutAndStderr() {
		verify(Stream.of("stderr:", stderr.toString(), "stdout:", stdout.toString()).collect(joining("\n")));
	}

	private void callMain(String... args) throws Exception {
		sipClientMainSpy.doMain(args);
	}

}
