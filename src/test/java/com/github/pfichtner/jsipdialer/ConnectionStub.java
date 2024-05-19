package com.github.pfichtner.jsipdialer;

import static java.util.Collections.emptyList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;
import com.github.pfichtner.jsipdialer.messages.SipStatus;
import com.github.pfichtner.jsipdialer.messages.Statuscode;

public class ConnectionStub implements Connection {

	private final List<MessageToSend> sent = new ArrayList<>();

	@Override
	public void send(MessageToSend message) throws IOException {
		sent.add(message);
		System.out.println("SENDING " + message);
	}

	@Override
	public MessageReceived receive() throws IOException {
		return null;
	}
	
	public List<MessageToSend> sent() {
		return sent;
	}

	static MessageReceived ok() {
		var status = SipStatus.OK;
		var data = Map.of( //
				"To", "ToXXX", //
				"From", "FromXXX", //
				"Via", "ViaXXX", //
				"Call-ID", "Call-IDXXX" //
		);
		return new MessageReceived("SIP/2.0 ", new Statuscode(status.value()), status.name(), data, emptyList());
	}

	@Override
	public String remoteServerAddress() {
		return "192.168.1.1";
	}

	@Override
	public int localPort() {
		return 45678;
	}

}