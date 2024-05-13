package com.github.pfichtner.jsipdial;

import static java.lang.Integer.parseInt;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import com.github.pfichtner.jsipdial.messages.MessageFactory;

public class SipClientMain {

	public static void main(String[] args) throws Exception {
		Properties properties = loadProperties("SipClientMain.config");

		var sipServerAddress = properties.getProperty("sipServerAddress");
		var sipServerPort = parseInt(properties.getProperty("sipServerPort"));
		var username = properties.getProperty("username");
		var password = properties.getProperty("password");

		var destinationNumber = properties.getProperty("destinationNumber");
		var callerName = properties.getProperty("callerName");
		var timeout = parseInt(properties.getProperty("timeout"));

		try (var connection = new Connection(sipServerAddress, sipServerPort, username, password)) {
			new CallExecutor(connection, new MessageFactory())
					.execCall(new Call(destinationNumber, callerName, timeout));
		}
	}

	private static Properties loadProperties(String filename) throws IOException, FileNotFoundException {
		Properties properties = new Properties();
		properties.load(new FileInputStream(filename));
		return properties;
	}

}
