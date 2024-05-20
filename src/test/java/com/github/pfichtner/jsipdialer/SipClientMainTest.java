package com.github.pfichtner.jsipdialer;

import static java.lang.System.lineSeparator;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class SipClientMainTest {

	private static final String ARGNAME_SIP_SERVER_ADDRESS = "sipServerAddress";
	private static final String ARGNAME_SIP_SERVER_PORT = "sipServerPort";
	private static final String ARGNAME_SIP_USERNAME = "sipUsername";
	private static final String ARGNAME_SIP_PASSWORD = "sipPassword";
	private static final String ARGNAME_CALLER_NAME = "callerName";
	private static final String ARGNAME_DESTINATION_NUMBER = "destinationNumber";
	private static final String ARGNAME_TIMEOUT = "timeout";

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

	Map<String, Object> params = checkValuesAreUnique(Map.of( //
			ARGNAME_SIP_SERVER_ADDRESS, "foobar.local", //
			ARGNAME_SIP_SERVER_PORT, SipClientMain.DEFAULT_SIPPORT + 1, //
			ARGNAME_SIP_USERNAME, "someUser", //
			ARGNAME_SIP_PASSWORD, "somePassword", //
			ARGNAME_CALLER_NAME, "someCallerName", //
			ARGNAME_DESTINATION_NUMBER, "12345", //
			ARGNAME_TIMEOUT, SipClientMain.DEFAULT_TIMEOUT + 1 //
	));

	SipClientMainSpy sipClientMainSpy = new SipClientMainSpy();
	ByteArrayOutputStream stdout = new ByteArrayOutputStream();
	ByteArrayOutputStream stderr = new ByteArrayOutputStream();

	@BeforeEach
	void setup() {
		System.setOut(new PrintStream(stdout));
		System.setErr(new PrintStream(stderr));
	}

	private static Map<String, Object> checkValuesAreUnique(Map<String, Object> map) {
		var values = map.values();
		if (Set.copyOf(values).size() < values.size()) {
			throw new IllegalStateException("Duplicate values in " + map + "(" + values + ")");
		}
		return map;
	}

	@Test
	void noArgsAtAll() throws Exception {
		callMain();
		assertThat(stderr.toString())
				.contains("Missing required options: " + stream(requiredArgs()).collect(joining(", ")));
		verifyStdoutAndStderr();
	}

	@Test
	void requiredArgumentsSetButUserAndPasswordMissing() throws Exception {
		callMain(setValuesOn(requiredArgs()));
		verifyStdoutAndStderr();
	}

	@Test
	void requiredArgumentsAndUserSetButPasswordMissing() throws Exception {
		callMain(setValuesOn(and(requiredArgs(), ARGNAME_SIP_USERNAME)));
		verifyStdoutAndStderr();
	}

	@Test
	void allRequiredParametersSet() throws Exception {
		callMain(setValuesOn(and(requiredArgs(), ARGNAME_SIP_USERNAME, ARGNAME_SIP_PASSWORD)));
		assertSoftly(c -> {
			c.assertThat(sipClientMainSpy.server).isEqualTo(value(ARGNAME_SIP_SERVER_ADDRESS));
			c.assertThat(sipClientMainSpy.call.getDestinationNumber()).isEqualTo(value(ARGNAME_DESTINATION_NUMBER));
			c.assertThat(sipClientMainSpy.sipConfig.getUsername()).isEqualTo(value(ARGNAME_SIP_USERNAME));
			c.assertThat(sipClientMainSpy.sipConfig.getPassword()).isEqualTo(value(ARGNAME_SIP_PASSWORD));
			c.assertThat(sipClientMainSpy.connection).isSameAs(sipClientMainSpy.theConnection);

			c.assertThat(sipClientMainSpy.port).isEqualTo(SipClientMain.DEFAULT_SIPPORT);
			c.assertThat(sipClientMainSpy.call.getCallerName()).isNull();
			c.assertThat(sipClientMainSpy.call.getTimeout()).isEqualTo(SipClientMain.DEFAULT_TIMEOUT);
		});
	}

	@Test
	@SetEnvironmentVariable(key = "SIP_USERNAME", value = "userNameViaEnv")
	@SetEnvironmentVariable(key = "SIP_PASSWORD", value = "passwordViaEnv")
	void allRequiredParametersSetWhereUsernameAndPasswordAreSetViaEnvVars() throws Exception {
		callMain(setValuesOn(requiredArgs()));
		assertSoftly(c -> {
			c.assertThat(sipClientMainSpy.sipConfig.getUsername()).isEqualTo("userNameViaEnv");
			c.assertThat(sipClientMainSpy.sipConfig.getPassword()).isEqualTo("passwordViaEnv");
		});
	}

	@Test
	void canSetOptionalValues() throws Exception {
		callMain(setValuesOn(and(requiredArgs(), ARGNAME_SIP_USERNAME, ARGNAME_SIP_PASSWORD, ARGNAME_SIP_SERVER_PORT,
				ARGNAME_CALLER_NAME, ARGNAME_TIMEOUT)));
		assertSoftly(c -> {
			c.assertThat(sipClientMainSpy.port).isEqualTo(value(ARGNAME_SIP_SERVER_PORT));
			c.assertThat(sipClientMainSpy.call.getCallerName()).isEqualTo(value(ARGNAME_CALLER_NAME));
			c.assertThat(sipClientMainSpy.call.getTimeout()).isEqualTo(value(ARGNAME_TIMEOUT));
		});
	}

	private String[] and(String[] strings, String... others) {
		return concat(Arrays.stream(strings), Arrays.stream(others)).toArray(String[]::new);
	}

	private String[] setValuesOn(String... in) {
		return Arrays.stream(in).map(p -> List.of("-" + p, String.valueOf(value(p)))).flatMap(Collection::stream)
				.toArray(String[]::new);
	}

	private static String[] requiredArgs() {
		return new String[] { ARGNAME_SIP_SERVER_ADDRESS, ARGNAME_DESTINATION_NUMBER };
	}

	private Object value(String parameter) {
		return params.get(parameter);
	}

	private void verifyStdoutAndStderr() {
		String[] stdErrLines = stderr.toString().split(lineSeparator());
		verify(Stream.of("stderr:", stdErrLines.length == 0 ? "" : stdErrLines[0], "", "stdout:", stdout.toString())
				.collect(joining("\n")));
	}

	private void callMain(String... args) throws Exception {
		sipClientMainSpy.doMain(args);
	}

}
