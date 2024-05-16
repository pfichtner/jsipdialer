package com.github.pfichtner.jsipdialer.messages;

import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.StreamSupport.stream;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MessageReceived {

	private static class ParseMessageReceived extends MessageReceived {

		private final String originalResponse;

		public ParseMessageReceived(String originalResponse, String proto, Statuscode statuscode, String status,
				Map<String, String> data, List<String> additional) {
			super(proto, statuscode, status, data, additional);
			this.originalResponse = originalResponse;
		}

		@Override
		public String toString() {
			return originalResponse;
		}

	}

	private final String proto;
	private final Statuscode statuscode;
	private final String status;
	private final Map<String, String> data;
	private final List<String> additional;

	public MessageReceived(String proto, Statuscode statuscode, String status, Map<String, String> data,
			List<String> additional) {
		this.proto = proto;
		this.statuscode = statuscode;
		this.status = status;
		this.data = data;
		this.additional = additional;
	}

	public static MessageReceived parse(String response) {
		Iterator<String> it = asList(response.split("\r\n")).iterator();
		String[] split = it.next().split("\\ ", 3);
		Map<String, String> data = stream(spliteratorUnknownSize(it, ORDERED), false) //
				.takeWhile(not(String::isEmpty)) //
				.map(l -> l.split(":", 2)) //
				.filter(p -> p.length == 2) //
				.collect(toMap(p -> p[0].trim(), parts -> parts[1].trim()) //
				);
		List<String> additional = stream(spliteratorUnknownSize(it, ORDERED), false).toList();
		return new ParseMessageReceived(response, split[0], new Statuscode(parseInt(split[1])), split[2], data,
				additional);
	}

	public String getStatus() {
		return status;
	}

	public Statuscode statuscode() {
		return statuscode;
	}

	public String get(String key) {
		String value = data.get(key);
		if (value == null) {
			throw new IllegalStateException(format("No value for key '%s' present in %s", key, this));
		}
		return value;
	}

	public List<String> getAdditional() {
		return additional;
	}

}