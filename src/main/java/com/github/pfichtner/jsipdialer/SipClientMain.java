package com.github.pfichtner.jsipdialer;

import static java.lang.Integer.parseInt;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.github.pfichtner.jsipdialer.messages.MessageFactory;

public class SipClientMain {

	private static final String TIMEOUT = "timeout";
	private static final String CALLER_NAME = "callerName";
	private static final String DESTINATION_NUMBER = "destinationNumber";

	private static final String PASSWORD = "sipPassword";
	private static final String USERNAME = "sipUsername";

	private static final String SIP_SERVER_PORT = "sipServerPort";
	private static final String SIP_SERVER_ADDRESS = "sipServerAddress";

	public static void main(String[] args) throws Exception {
		var options = options();
		var parser = new DefaultParser();

		try {
			var cmdLine = parser.parse(options, args);
			try (var connection = new Connection(cmdLine.getOptionValue(SIP_SERVER_ADDRESS),
					parseInt(cmdLine.getOptionValue(SIP_SERVER_PORT, "5060")))) {
				SipConfig config = new SipConfig(cmdLine.getOptionValue(USERNAME, System.getenv("SIP_USERNAME")),
						cmdLine.getOptionValue(PASSWORD, System.getenv("SIP_PASSWORD")));
				new CallExecutor(connection, config, new MessageFactory()).execCall(new Call( //
						cmdLine.getOptionValue(DESTINATION_NUMBER), //
						cmdLine.getOptionValue(CALLER_NAME), //
						parseInt(cmdLine.getOptionValue(TIMEOUT, "15")) //
				));
			}
		} catch (ParseException e) {
			e.printStackTrace();
			new HelpFormatter().printHelp("args...", options);
		}

	}

	private static Options options() {
		return new Options() //
				.addOption(SIP_SERVER_ADDRESS, true, "") //
				.addOption(SIP_SERVER_PORT, true, "") //
				.addOption(USERNAME, true, "") //
				.addOption(PASSWORD, true, "") //
				.addOption(DESTINATION_NUMBER, true, "") //
				.addOption(CALLER_NAME, true, "") //
				.addOption(TIMEOUT, true, "");
	}

}
