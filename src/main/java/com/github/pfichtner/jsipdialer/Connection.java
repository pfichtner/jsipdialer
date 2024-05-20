package com.github.pfichtner.jsipdialer;

import java.io.IOException;

import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;

public interface Connection extends AutoCloseable {

	void send(MessageToSend message) throws IOException;

	MessageReceived receive() throws IOException;

	String remoteServerAddress();

	int localPort();

}