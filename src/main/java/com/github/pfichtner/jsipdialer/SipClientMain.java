package com.github.pfichtner.jsipdialer;

import java.util.Optional;
import java.util.function.Supplier;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class SipClientMain {

	private static final String JSIPDIALER = "jsipdialer";

	public static final int DEFAULT_SIPPORT = 5060;
	public static final int DEFAULT_TIMEOUT = 15;
	public static final String DEFAULT_TRANSPORT = "udp";

	public static final String ENVVAR_SIP_USERNAME = "SIP_USERNAME";
	public static final String ENVVAR_SIP_PASSWORD = "SIP_PASSWORD";

	public static final String DESTINATION_NUMBER = "destinationNumber";
	public static final String CALLER_NAME = "callerName";
	public static final String TIMEOUT = "timeout";
	public static final String TRANSPORT = "transport";

	public static final String USERNAME = "sipUsername";
	public static final String PASSWORD = "sipPassword";

	public static final String SIP_SERVER_ADDRESS = "sipServerAddress";
	public static final String SIP_SERVER_PORT = "sipServerPort";

	private static final java.util.List<String> SUPPORTED_TRANSPORTS = java.util.List.of(DEFAULT_TRANSPORT, "tcp");

	public static void main(String... args) throws Exception {
		var exitCode = new SipClientMain().doMain(args);
		System.exit(exitCode);
	}

	public int doMain(String[] args) throws Exception {
		var options = options();
		var parser = new DefaultParser();

		try {
			var cmdLine = parser.parse(options, args);
			var sipConfig = new SipConfig(
					requireNonNull(cmdLine.getOptionValue(USERNAME, env(ENVVAR_SIP_USERNAME)),
							envErrorMessage(USERNAME, ENVVAR_SIP_USERNAME)),
					requireNonNull(cmdLine.getOptionValue(PASSWORD, env(ENVVAR_SIP_PASSWORD)),
							envErrorMessage(PASSWORD, ENVVAR_SIP_PASSWORD)));
			if (cmdLine.hasOption(USERNAME) || cmdLine.hasOption(PASSWORD)) {
				System.err.println("WARNING: The SIP credentials were passed as command line arguments. "
						+ "This is insecure because they may be visible to other processes on the system. "
						+ "Prefer the " + ENVVAR_SIP_USERNAME + " and " + ENVVAR_SIP_PASSWORD
						+ " environment variables.");
				System.err.flush();
			}
			var serverAddress = cmdLine.getOptionValue(SIP_SERVER_ADDRESS);
			var serverPort = parseIntOption(cmdLine, SIP_SERVER_PORT, DEFAULT_SIPPORT, 1, 65535);
			var destinationNumber = cmdLine.getOptionValue(DESTINATION_NUMBER);
			var callerName = cmdLine.getOptionValue(CALLER_NAME);
			var timeout = parseIntOption(cmdLine, TIMEOUT, DEFAULT_TIMEOUT, 0, Integer.MAX_VALUE);
			var transport = cmdLine.getOptionValue(TRANSPORT, DEFAULT_TRANSPORT).toLowerCase();
			if (!SUPPORTED_TRANSPORTS.contains(transport)) {
				throw new ParseException(
						"Unsupported transport '%s', must be one of %s".formatted(transport, SUPPORTED_TRANSPORTS));
			}
			validateNoControlCharacters(serverAddress, SIP_SERVER_ADDRESS);
			validateNoControlCharacters(destinationNumber, DESTINATION_NUMBER);
			validateNoControlCharacters(callerName, CALLER_NAME);
			validateNoControlCharacters(sipConfig.username(), USERNAME);

			var callService = createCallService(serverAddress, serverPort, sipConfig.username(),
					sipConfig.password(), destinationNumber, callerName, timeout, transport);
			if (!callService.call()) {
				System.err.println("Call failed: " + callService.getReason());
				return 1;
			}
			return 0;
		} catch (ParseException e) {
			e.printStackTrace();
			new HelpFormatter().printHelp(binaryName(), options);
			return 1;
		}
	}

	protected CallService createCallService(String serverAddress, int serverPort, String username,
			String password, String destinationNumber, String callerName, int timeout, String transport) {
		return new CallService(serverAddress, serverPort, username, password,
				destinationNumber, callerName, timeout, transport);
	}

	private static String binaryName() {
		Optional<String> binaryName = System.getProperty("org.graalvm.nativeimage.imagecode") == null
				? Optional.empty()
				: ProcessHandle.current().info().command().map(c -> c.substring(c.lastIndexOf('/') + 1));
		return binaryName.orElse(JSIPDIALER);
	}

	private static String requireNonNull(String value, String errorMessage) throws ParseException {
		if (value == null) {
			throw new ParseException(errorMessage);
		}
		return value;
	}

	private static int parseIntOption(CommandLine cmdLine, String option, int defaultValue, int min, int max)
			throws ParseException {
		String value = cmdLine.getOptionValue(option, String.valueOf(defaultValue));
		int parsed;
		try {
			parsed = Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new ParseException(
					"Invalid value for '%s': '%s' is not a valid integer".formatted(option, value));
		}
		if (parsed < min || parsed > max) {
			throw new ParseException(
					"Invalid value for '%s': '%s' is outside the allowed range [%d, %d]".formatted(option, value, min,
							max));
		}
		return parsed;
	}

	private static void validateNoControlCharacters(String value, String name) throws ParseException {
		if (value != null && value.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
			throw new ParseException(
					"Invalid value for '%s': control characters are not allowed".formatted(name));
		}
	}

	private static Options options() {
		return new Options()
				.addRequiredOption(SIP_SERVER_ADDRESS, null, true, "ip/name of the sip server")
				.addOption(SIP_SERVER_PORT, null, true, "port number of the sip server")
				.addOption(USERNAME, true,
						"sip username (should better be passed via env var " + ENVVAR_SIP_USERNAME + ")")
				.addOption(PASSWORD, true,
						"sip password (should better be passed via env var " + ENVVAR_SIP_PASSWORD + ")")
				.addRequiredOption(DESTINATION_NUMBER, null, true, "the number to call")
				.addOption(CALLER_NAME, null, true, "the caller's name that gets displayed")
				.addOption(TIMEOUT, true, "terminate call at most after x seconds")
				.addOption(TRANSPORT, true, "transport protocol to use (udp or tcp)");
	}

	private static String envErrorMessage(String name, String envVar) {
		return "%s must be set via command line argument or environment variable '%s'".formatted(name, envVar);
	}

	private static Supplier<String> env(String envName) {
		return () -> System.getenv(envName);
	}

}
