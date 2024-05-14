package com.github.pfichtner.jsipdial;

import static com.github.pfichtner.jsipdial.messages.SipStatus.BUSY_HERE;
import static com.github.pfichtner.jsipdial.messages.SipStatus.DECLINE;
import static com.github.pfichtner.jsipdial.messages.SipStatus.OK;
import static com.github.pfichtner.jsipdial.messages.SipStatus.REQUEST_CANCELLED;
import static com.github.pfichtner.jsipdial.messages.SipStatus.TRYING;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.pfichtner.jsipdial.messages.MessageFactory;
import com.github.pfichtner.jsipdial.messages.MessageReceived;
import com.github.pfichtner.jsipdial.messages.MessageToSend;

public class CallExecutor {

	private final Connection connection;
	private final MessageFactory factory;

	public CallExecutor(Connection connection, MessageFactory messageFactory) {
		this.connection = connection;
		this.factory = messageFactory;
	}

	public void execCall(Call call) throws Exception {
		call.isInProgress(true);
		while (call.isInProgress()) {
			if (call.shouldTryInvite()) {
				call.increaseInvites();
				connection.send(inviteMessage(call));
			}

			if (call.isTimedout()) {
				connection.send(byeMessage(call));
			}

			call.received = connection.receive();
			var statuscode = call.received.statuscode();

			if (statuscode.is(OK)) {
				connection.send(ackMessage(call));
			} else if (statuscode.is(TRYING)) {
				connection.send(ackMessage(call));
			} else if (statuscode.isOneOf(BUSY_HERE, DECLINE, REQUEST_CANCELLED)) {
				connection.send(ackMessage(call));
				call.isInProgress(false);
			} else if (statuscode.isUnauthorized() && call.shouldTryInviteWithAuth()) {
				call.increaseInvitesWithAuth();
				connection.send(addAuthorization(call, inviteMessage(call)));
			}
		}
	}

	private MessageToSend addAuthorization(Call call, MessageToSend inviteMessage) {
		var wwwAuthenticate = call.received.get("WWW-Authenticate");
		var realm = extractValue(wwwAuthenticate, "realm");
		var nonce = extractValue(wwwAuthenticate, "nonce");
		return inviteMessage.add("Authorization", digest(call, realm, nonce));
	}

	private String digest(Call call, String realm, String nonce) {
		var hash1 = md5Hash(format("%s:%s:%s", connection.username, realm, connection.password));
		var hash2 = md5Hash(format("INVITE:sip:%s@%s", call.destinationNumber, connection.sipServerAddress));
		var entries = Map.of( //
				"username", connection.username, //
				"realm=", realm, //
				"nonce", nonce, //
				"uri", sipDestination(call), //
				"response", md5Hash(format("%s:%s:%s", hash1, nonce, hash2)), //
				"algorithm", "MD5");
		return "Digest "
				+ entries.entrySet().stream().map(e -> e.getKey() + "=\"" + e.getValue() + "\"").collect(joining(", "));
	}

	private MessageToSend inviteMessage(Call call) {
		var lAddr = connection.localIpAddress();
		var lPort = connection.localPort();
		String sipDestination = sipDestination(call);
		return factory.newMessage("INVITE", sipDestination) //
				.add("Call-ID", "%010d@%s", call.callId, lAddr) //
				.add("From", "\"%s\" <sip:%s@%s>;tag=%010d", call.callerName, connection.username,
						connection.sipServerAddress, call.tagId)
				.add("Via", "%s/UDP %s:%d;rport=%d", factory.sipVersion(), lAddr, lPort, lPort)
				.add("To", "<" + sipDestination + ">") //
				.add("Contact", "\"%s\" <sip:%s@%s:%d;transport=udp>", connection.username, connection.username, lAddr,
						lPort);
	}

	private MessageToSend ackMessage(Call call) {
		Pattern pattern = Pattern.compile("<(.*?)>");
		Matcher matcher = pattern.matcher(call.received.get("To"));
		String ca = matcher.find() ? matcher.group(1) : null;
		return copyFromViaToFromAndLastCallFromLastReceived(call.received, factory.newMessage("ACK", ca));
	}

	private MessageToSend byeMessage(Call call) {
		return copyFromViaToFromAndLastCallFromLastReceived(call.received,
				factory.newMessage("BYE", sipDestination(call)));
	}

	private static MessageToSend copyFromViaToFromAndLastCallFromLastReceived(MessageReceived received,
			MessageToSend message) {
		return received == null //
				? message //
				: message //
						.addCopied("From", received) //
						.addCopied("Via", received) //
						.addCopied("To", received) //
						.addCopied("Call-ID", received) //
		;
	}

	private String sipDestination(Call call) {
		return format("sip:%s@%s", call.destinationNumber, connection.sipServerAddress);
	}

	private static String extractValue(String text, String key) {
		Pattern pattern = Pattern.compile(key + "=\"([^\"]+)\"");
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String md5Hash(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			StringBuilder hexString = new StringBuilder();
			for (byte hashByte : md.digest(input.getBytes())) {
				hexString.append(format("%02x", hashByte & 0xFF));
			}
			return hexString.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
