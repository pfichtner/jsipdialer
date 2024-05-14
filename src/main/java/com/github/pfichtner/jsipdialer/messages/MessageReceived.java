package com.github.pfichtner.jsipdialer.messages;

import static java.lang.Integer.parseInt;
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

	final String proto;
	private final Statuscode statuscode;
	private final String status;
	final Map<String, String> data;
	final List<String> additional;

	MessageReceived(String response) {
		Iterator<String> it = asList(response.split("\r\n")).iterator();
		String header = it.next();
		String[] split = header.split("\\ ", 3);
		this.proto = split[0];
		this.statuscode = new Statuscode(parseInt(split[1]));
		this.status = split[2];
		this.data = stream(spliteratorUnknownSize(it, ORDERED), false) //
				.takeWhile(not(String::isEmpty)) //
				.map(l -> l.split(":", 2)) //
				.filter(p -> p.length == 2) //
				.collect(toMap(p -> p[0].trim(), parts -> parts[1].trim()) //
				);
		this.additional = stream(spliteratorUnknownSize(it, ORDERED), false).toList();
	}

	public static MessageReceived parse(String response) {
		return new MessageReceived(response);
	}

	public String getStatus() {
		return status;
	}

	public Statuscode statuscode() {
		return statuscode;
	}

	public String get(String key) {
		return data.get(key);
	}

}