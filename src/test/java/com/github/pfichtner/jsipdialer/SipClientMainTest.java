package com.github.pfichtner.jsipdialer;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;
import static org.approvaltests.Approvals.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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

	private static final String ARGNAME_SIP_SERVER_ADDRESS = SipClientMain.SIP_SERVER_ADDRESS;
	private static final String ARGNAME_SIP_SERVER_PORT = SipClientMain.SIP_SERVER_PORT;
	private static final String ARGNAME_SIP_USERNAME = SipClientMain.USERNAME;
	private static final String ARGNAME_SIP_PASSWORD = SipClientMain.PASSWORD;
	private static final String ARGNAME_CALLER_NAME = SipClientMain.CALLER_NAME;
	private static final String ARGNAME_DESTINATION_NUMBER = SipClientMain.DESTINATION_NUMBER;
	private static final String ARGNAME_TIMEOUT = SipClientMain.TIMEOUT;

	private static final class SipClientMainSpy extends SipClientMain {

		String serverAddress;
		int serverPort;
		String username;
		String password;
		String destinationNumber;
		String callerName;
		int timeout;
		String transport;
		boolean callServiceReturn;

		@Override
		protected CallService createCallService(String serverAddress, int serverPort, String username,
				String password, String destinationNumber, String callerName, int timeout) {
			this.serverAddress = serverAddress;
			this.serverPort = serverPort;
			this.username = username;
			this.password = password;
			this.destinationNumber = destinationNumber;
			this.callerName = callerName;
			this.timeout = timeout;
			this.transport = "udp";
			return new CallService(null, 0, null, null, null, null, 0, null) {
				@Override
				public boolean call() {
					return callServiceReturn;
				}
			};
		}

	}

	final Map<String, Object> params = checkValuesAreUnique(Map.of( //
			ARGNAME_SIP_SERVER_ADDRESS, "some.server.address.local", //
			ARGNAME_SIP_SERVER_PORT, SipClientMain.DEFAULT_SIPPORT + 1, //
			ARGNAME_SIP_USERNAME, "someUser", //
			ARGNAME_SIP_PASSWORD, "somePassword", //
			ARGNAME_CALLER_NAME, "someCallerName", //
			ARGNAME_DESTINATION_NUMBER, "12345", //
			ARGNAME_TIMEOUT, SipClientMain.DEFAULT_TIMEOUT + 1 //
	));

	final SipClientMainSpy sipClientMainSpy = new SipClientMainSpy();

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
				.contains("Missing required options: " + String.join(", ", requiredArgs()));
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
			s.assertThat(sipClientMainSpy.serverAddress).isEqualTo(value(ARGNAME_SIP_SERVER_ADDRESS));
			s.assertThat(sipClientMainSpy.destinationNumber).isEqualTo(value(ARGNAME_DESTINATION_NUMBER));
			s.assertThat(sipClientMainSpy.username).isEqualTo(value(ARGNAME_SIP_USERNAME));
			s.assertThat(sipClientMainSpy.password).isEqualTo(value(ARGNAME_SIP_PASSWORD));
			s.assertThat(sipClientMainSpy.serverPort).isEqualTo(SipClientMain.DEFAULT_SIPPORT);
			s.assertThat(sipClientMainSpy.callerName).isNull();
			s.assertThat(sipClientMainSpy.timeout).isEqualTo(SipClientMain.DEFAULT_TIMEOUT);
			s.assertThat(sipClientMainSpy.transport).isEqualTo("udp");
		});
	}

	@Test
	@SetEnvironmentVariable(key = SipClientMain.ENVVAR_SIP_USERNAME, value = "userNameViaEnv")
	@SetEnvironmentVariable(key = SipClientMain.ENVVAR_SIP_PASSWORD, value = "passwordViaEnv")
	void allRequiredParametersSetWhereUsernameAndPasswordAreSetViaEnvVars() throws Exception {
		callMain(setValuesOn(requiredArgs()));
		assertSoftly(s -> {
			s.assertThat(sipClientMainSpy.username).isEqualTo("userNameViaEnv");
			s.assertThat(sipClientMainSpy.password).isEqualTo("passwordViaEnv");
		});
	}

	@Test
	void canSetOptionalValues() throws Exception {
		callMain(setValuesOn(and(requiredArgs(), ARGNAME_SIP_USERNAME, ARGNAME_SIP_PASSWORD, ARGNAME_SIP_SERVER_PORT,
				ARGNAME_CALLER_NAME, ARGNAME_TIMEOUT)));
		assertSoftly(s -> {
			s.assertThat(sipClientMainSpy.serverPort).isEqualTo(value(ARGNAME_SIP_SERVER_PORT));
			s.assertThat(sipClientMainSpy.callerName).isEqualTo(value(ARGNAME_CALLER_NAME));
			s.assertThat(sipClientMainSpy.timeout).isEqualTo(value(ARGNAME_TIMEOUT));
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
		verify(String.join("\n", "stderr:", stream(stderr.capturedLines()).limit(1).collect(joining("\n")), "", "stdout:",
                join(stdout.capturedLines())));
	}

	private static String join(String[] capturedLines) {
		return String.join("\n", capturedLines);
	}

	private void callMain(String... args) throws Exception {
		sipClientMainSpy.doMain(args);
	}

}
