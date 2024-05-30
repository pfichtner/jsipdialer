package com.github.pfichtner.jsipdialer;

import static java.lang.Integer.parseInt;

import java.util.Optional;
import java.util.function.Supplier;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class SipClientMain {

	private static final String JSIPDIALER = "jsipdialer";

	public static final int DEFAULT_SIPPORT = 5060;
	public static final int DEFAULT_TIMEOUT = 15;

	private static final String ENVVAR_SIP_USERNAME = "SIP_USERNAME";
	private static final String ENVVAR_SIP_PASSWORD = "SIP_PASSWORD";

	private static final String DESTINATION_NUMBER = "destinationNumber";
	private static final String CALLER_NAME = "callerName";
	private static final String TIMEOUT = "timeout";

	private static final String USERNAME = "sipUsername";
	private static final String PASSWORD = "sipPassword";

	private static final String SIP_SERVER_ADDRESS = "sipServerAddress";
	private static final String SIP_SERVER_PORT = "sipServerPort";

	public static void main(String... args) throws Exception {
		new SipClientMain().doMain(args);
	}

	void doMain(String[] args) throws Exception {
		var options = options();
		var parser = new DefaultParser();

		try {
			var cmdLine = parser.parse(options, args);
			var sipConfig = new SipConfig(
					requireNonNull(cmdLine.getOptionValue(USERNAME, env(ENVVAR_SIP_USERNAME)),
							envErrorMessage(USERNAME, ENVVAR_SIP_USERNAME)),
					requireNonNull(cmdLine.getOptionValue(PASSWORD, env(ENVVAR_SIP_PASSWORD)),
							envErrorMessage(PASSWORD, ENVVAR_SIP_PASSWORD)));
			var call = new Call( //
					cmdLine.getOptionValue(DESTINATION_NUMBER), //
					cmdLine.getOptionValue(CALLER_NAME), //
					parseInt(cmdLine.getOptionValue(TIMEOUT, String.valueOf(DEFAULT_TIMEOUT))) //
			);
			try (Connection connection = makeConnection(cmdLine.getOptionValue(SIP_SERVER_ADDRESS),
					parseInt(cmdLine.getOptionValue(SIP_SERVER_PORT, String.valueOf(DEFAULT_SIPPORT))))) {
				execCall(sipConfig, call, connection);
			}
		} catch (ParseException e) {
			e.printStackTrace();
			new HelpFormatter().printHelp(binaryName(), options);
		}
	}

	private static String binaryName() {
		Optional<String> binaryName = System.getProperty("org.graalvm.nativeimage.imagecode") == null //
				? Optional.empty() //
				: ProcessHandle.current().info().command().map(c -> c.substring(c.lastIndexOf('/') + 1));
		return binaryName.orElse(JSIPDIALER);
	}

	protected Connection makeConnection(String server, int port) throws Exception {
		return new UdpConnection(server, port);
	}

	protected void execCall(SipConfig sipConfig, Call call, Connection connection) throws Exception {
		new CallExecutor(connection, sipConfig).execCall(call);
	}

	private static String requireNonNull(String value, String errorMessage) throws ParseException {
		if (value == null) {
			throw new ParseException(errorMessage);
		}
		return value;
	}

	private static Options options() {
		return new Options() //
				.addRequiredOption(SIP_SERVER_ADDRESS, null, true, "ip/name of the sip server") //
				.addOption(SIP_SERVER_PORT, null, true, "port number of the sip server") //
				.addOption(USERNAME, true,
						"sip username (should better be passed via env var " + ENVVAR_SIP_USERNAME + ")") //
				.addOption(PASSWORD, true,
						"sip password (should better be passed via env var " + ENVVAR_SIP_PASSWORD + ")") //
				.addRequiredOption(DESTINATION_NUMBER, null, true, "the number to call") //
				.addOption(CALLER_NAME, null, true, "the caller's name that gets displayed") //
				.addOption(TIMEOUT, true, "terminate call at most after x seconds");
	}

	private static String envErrorMessage(String name, String envVar) {
		return "%s must be set via command line argument or environment variable '%s'".formatted(name, envVar);
	}

	private static Supplier<String> env(String envName) {
		return () -> System.getenv(envName);
	}

}
