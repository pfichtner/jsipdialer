package com.github.pfichtner.jsipdialer.messages;

import static java.lang.String.format;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public class MessageToSend {

	private final String command;
	private final String content;
	private final String proto;
	private final Map<String, String> lines = new LinkedHashMap<>();

	MessageToSend(String command, String content, String proto) {
		this.command = command;
		this.content = content;
		this.proto = proto;
	}

	public MessageToSend add(String name, String content, Object... args) {
		this.lines.put(name, format(content, args));
		return this;
	}

	public MessageToSend addCopied(String key, MessageReceived received) {
		return add(key, received.get(key));
	}

	@Override
	public String toString() {
		return Stream.of( //
				Stream.of(header()), //
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

	private static Stream<String> transform(Map<String, String> lines) {
		return lines.entrySet().stream().map(e -> format("%s: %s", e.getKey(), e.getValue()));
	}

}