package com.github.pfichtner.jsipdial.messages;

public class MessageFactory {

	private static final String SIP_VERSION = "SIP/2.0";

	private int cSeq = 1;

	public MessageToSend newMessage(String command, String content) {
		return new MessageToSend(command, content, sipVersion()).add("CSeq", nextSeq() + " " + command);
	}

	private int nextSeq() {
		return cSeq++;
	}

	public String sipVersion() {
		return SIP_VERSION;
	}
}
