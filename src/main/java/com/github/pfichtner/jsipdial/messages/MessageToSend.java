package com.github.pfichtner.jsipdial.messages;

import static java.lang.String.format;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public class MessageToSend {

	private final String header;
	private final Map<String, String> lines = new LinkedHashMap<>();

	MessageToSend(String command, String content, String proto) {
		this.header = Stream.of(command, content, proto).collect(joining(" "));
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
				Stream.of(header), //
				transform(lines), //
				Stream.of("Content-Length: 0"), //
				Stream.of("") //
		).flatMap(identity()).collect(joining("\r\n"));
	}

	private static Stream<String> transform(Map<String, String> lines) {
		return lines.entrySet().stream().map(e -> format("%s: %s", e.getKey(), e.getValue()));
	}

}