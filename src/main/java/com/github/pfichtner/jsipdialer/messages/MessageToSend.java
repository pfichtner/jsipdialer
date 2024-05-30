package com.github.pfichtner.jsipdialer.messages;

import static com.github.pfichtner.jsipdialer.messages.CSeq.C_SEQ;
import static com.github.pfichtner.jsipdialer.messages.Constants.SIP_VERSION;
import static java.lang.String.format;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public class MessageToSend {

	private final String command;
	private final CSeq cSeq;
	private final String content;
	private final String proto;
	private final Map<String, String> lines;

	public static MessageToSend newMessage(CSeq cSeq, String command, String content) {
		return new MessageToSend(cSeq, command, content, SIP_VERSION);
	}

	private MessageToSend(CSeq cSeq, String command, String content, String proto) {
		this(cSeq, command, content, proto, new LinkedHashMap<>());
	}

	private MessageToSend(CSeq cSeq, String command, String content, String proto, Map<String, String> lines) {
		this.cSeq = cSeq;
		this.command = command;
		this.content = content;
		this.proto = proto;
		this.lines = lines;
	}

	public MessageToSend add(String name, String content, Object... args) {
		this.lines.put(name, format(content, args));
		return this;
	}

	@Override
	public String toString() {
		return Stream.of( //
				Stream.of(header()), //
				Stream.of(keyValue(C_SEQ, "%d %s".formatted(cSeq.sequence(), command))), //
				transform(lines), //
				Stream.of("Content-Length: 0"), //
				Stream.of("") //
		).flatMap(identity()).collect(joining("\r\n"));
	}

	public String header() {
		return Stream.of(command, content, proto).collect(joining(" "));
	}

	public Map<String, String> lines() {
		return lines;
	}

	public String command() {
		return command;
	}

	public CSeq cSeq() {
		return cSeq;
	}

	public MessageToSend withCseq(CSeq otherCSeq) {
		return new MessageToSend(otherCSeq, command, content, proto, new LinkedHashMap<>(lines));
	}

	private static Stream<String> transform(Map<String, String> lines) {
		return lines.entrySet().stream().map(e -> keyValue(e.getKey(), e.getValue()));
	}

	private static String keyValue(String key, String value) {
		return format("%s: %s", key, value);
	}

	public MessageToSend addCopied(String key, MessageToSend copyFrom) {
		lines.put(key, copyFrom.lines.get(key));
		return this;
	}

}