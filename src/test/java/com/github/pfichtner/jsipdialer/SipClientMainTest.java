package com.github.pfichtner.jsipdialer;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;
import static org.approvaltests.Approvals.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.junitpioneer.jupiter.StdErr;
import org.junitpioneer.jupiter.StdIo;
import org.junitpioneer.jupiter.StdOut;
import org.junitpioneer.jupiter.WritesStdIo;

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
		private Integer port;
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
			ARGNAME_SIP_SERVER_ADDRESS, "some.server.address.local", //
			ARGNAME_SIP_SERVER_PORT, SipClientMain.DEFAULT_SIPPORT + 1, //
			ARGNAME_SIP_USERNAME, "someUser", //
			ARGNAME_SIP_PASSWORD, "somePassword", //
			ARGNAME_CALLER_NAME, "someCallerName", //
			ARGNAME_DESTINATION_NUMBER, "12345", //
			ARGNAME_TIMEOUT, SipClientMain.DEFAULT_TIMEOUT + 1 //
	));

	SipClientMainSpy sipClientMainSpy = new SipClientMainSpy();

	private static <K, V> Map<K, V> checkValuesAreUnique(Map<K, V> map) {
		var values = map.values();
		if (Set.copyOf(values).size() < values.size()) {
			throw new IllegalStateException("Duplicate values in " + map + "(" + values + ")");
		}
		return map;
	}

	@Test
	@StdIo
	@WritesStdIo
	void noArgsAtAll(StdOut stdOut, StdErr stderr) throws Exception {
		callMain();
		assertThat(join(stderr.capturedLines()))
				.contains("Missing required options: " + stream(requiredArgs()).collect(joining(", ")));
		verifyStdoutAndStderr(stdOut, stderr);
	}

	@Test
	@StdIo
	@WritesStdIo
	void requiredArgumentsSetButUserAndPasswordMissing(StdOut stdOut, StdErr stderr) throws Exception {
		callMain(setValuesOn(requiredArgs()));
		verifyStdoutAndStderr(stdOut, stderr);
	}

	@Test
	@StdIo
	@WritesStdIo
	void requiredArgumentsAndUserSetButPasswordMissing(StdOut stdOut, StdErr stderr) throws Exception {
		callMain(setValuesOn(and(requiredArgs(), ARGNAME_SIP_USERNAME)));
		verifyStdoutAndStderr(stdOut, stderr);
	}

	@Test
	void allRequiredParametersSet() throws Exception {
		callMain(setValuesOn(and(requiredArgs(), ARGNAME_SIP_USERNAME, ARGNAME_SIP_PASSWORD)));
		assertSoftly(s -> {
			s.assertThat(sipClientMainSpy.server).isEqualTo(value(ARGNAME_SIP_SERVER_ADDRESS));
			s.assertThat(sipClientMainSpy.call.destinationNumber()).isEqualTo(value(ARGNAME_DESTINATION_NUMBER));
			s.assertThat(sipClientMainSpy.sipConfig.getUsername()).isEqualTo(value(ARGNAME_SIP_USERNAME));
			s.assertThat(sipClientMainSpy.sipConfig.getPassword()).isEqualTo(value(ARGNAME_SIP_PASSWORD));
			s.assertThat(sipClientMainSpy.connection).isSameAs(sipClientMainSpy.theConnection);

			s.assertThat(sipClientMainSpy.port).isEqualTo(SipClientMain.DEFAULT_SIPPORT);
			s.assertThat(sipClientMainSpy.call.callerName()).isNull();
			s.assertThat(sipClientMainSpy.call.timeout()).isEqualTo(SipClientMain.DEFAULT_TIMEOUT);
		});
	}

	@Test
	@SetEnvironmentVariable(key = "SIP_USERNAME", value = "userNameViaEnv")
	@SetEnvironmentVariable(key = "SIP_PASSWORD", value = "passwordViaEnv")
	void allRequiredParametersSetWhereUsernameAndPasswordAreSetViaEnvVars() throws Exception {
		callMain(setValuesOn(requiredArgs()));
		assertSoftly(s -> {
			s.assertThat(sipClientMainSpy.sipConfig.getUsername()).isEqualTo("userNameViaEnv");
			s.assertThat(sipClientMainSpy.sipConfig.getPassword()).isEqualTo("passwordViaEnv");
		});
	}

	@Test
	void canSetOptionalValues() throws Exception {
		callMain(setValuesOn(and(requiredArgs(), ARGNAME_SIP_USERNAME, ARGNAME_SIP_PASSWORD, ARGNAME_SIP_SERVER_PORT,
				ARGNAME_CALLER_NAME, ARGNAME_TIMEOUT)));
		assertSoftly(s -> {
			s.assertThat(sipClientMainSpy.port).isEqualTo(value(ARGNAME_SIP_SERVER_PORT));
			s.assertThat(sipClientMainSpy.call.callerName()).isEqualTo(value(ARGNAME_CALLER_NAME));
			s.assertThat(sipClientMainSpy.call.timeout()).isEqualTo(value(ARGNAME_TIMEOUT));
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

	private void verifyStdoutAndStderr(StdOut stdout, StdErr stderr) {
		verify(Stream.of("stderr:", stream(stderr.capturedLines()).limit(1).collect(joining("\n")), "", "stdout:",
				join(stdout.capturedLines())).collect(joining("\n")));
	}

	private static String join(String[] capturedLines) {
		return stream(capturedLines).collect(joining("\n"));
	}

	private void callMain(String... args) throws Exception {
		sipClientMainSpy.doMain(args);
	}

}
