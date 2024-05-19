package com.github.pfichtner.jsipdialer;

import static com.github.pfichtner.jsipdialer.messages.SipStatus.BUSY_HERE;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.CALL_DOES_NOT_EXIST;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.DECLINE;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.OK;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.REQUEST_CANCELLED;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.TRYING;
import static java.lang.String.format;
import static java.net.NetworkInterface.getNetworkInterfaces;
import static java.util.Collections.list;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.joining;

import java.net.InetAddress;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.pfichtner.jsipdialer.messages.MessageFactory;
import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;

public class CallExecutor {

	private static final Logger logger = Logger.getLogger(CallExecutor.class.getName());

	private final String locIpAddr = localIpAddress().map(InetAddress::getHostAddress).orElse("0.0.0.0");

	private final Connection connection;
	private final SipConfig config;
	private final MessageFactory factory;

	public CallExecutor(Connection connection, SipConfig config, MessageFactory messageFactory) {
		this.connection = connection;
		this.config = config;
		this.factory = messageFactory;
	}

	public void execCall(Call call) throws Exception {
		call.inProgress(true);
		while (call.isInProgress() && !call.shouldGiveUp()) {
			if (call.shouldTryInvite()) {
				call.increaseInvites();
				connection.send(inviteMessage(call));
			}

			if (call.shouldTryBye()) {
				call.increaseBye();
				connection.send(byeMessage(call));
			}

			MessageReceived next = connection.receive();
			if (next == null) {
				continue;
			}

			call.setReceived(next);

			if (call.statuscode().is(OK)) {
				connection.send(ackMessage(call));
			} else if (call.statuscode().is(TRYING)) {
				connection.send(ackMessage(call));
			} else if (call.statuscode().isOneOf(BUSY_HERE, DECLINE, REQUEST_CANCELLED, CALL_DOES_NOT_EXIST)) {
				if (call.statuscode().is(CALL_DOES_NOT_EXIST)) {
					logger.log(SEVERE, "Error on call handling %s", call.received);
				}
				connection.send(ackMessage(call));
				call.inProgress(false);
			} else if (call.statuscode().isUnauthorized() && call.shouldTryInviteWithAuth()) {
				call.increaseInvitesWithAuth();
				connection.send(addAuthorization(call, inviteMessage(call)));
			}
		}
	}

	private MessageToSend addAuthorization(Call call, MessageToSend inviteMessage) {
		var wwwAuthenticate = call.received.get("WWW-Authenticate");
		var realm = extractValue(wwwAuthenticate, "realm");
		var nonce = extractValue(wwwAuthenticate, "nonce");
		var algorithm = tryExtractValue(wwwAuthenticate, "algorithm").orElse("MD5");
		return inviteMessage.add("Authorization", digest(call, realm, nonce, algorithm));
	}

	private String digest(Call call, String realm, String nonce, String algorithm) {
		var hash1 = hash(algorithm, format("%s:%s:%s", config.getUsername(), realm, config.getPassword()));
		var hash2 = hash(algorithm,
				format("INVITE:sip:%s@%s", call.getDestinationNumber(), connection.remoteServerAddress()));
		var entries = new LinkedHashMap<String, String>();
		entries.put("username", config.getUsername()); //
		entries.put("realm", realm); //
		entries.put("nonce", nonce); //
		entries.put("uri", sipIdentifier(call.getDestinationNumber())); //
		entries.put("response", hash(algorithm, format("%s:%s:%s", hash1, nonce, hash2))); //
		entries.put("algorithm", algorithm);
		return "Digest "
				+ entries.entrySet().stream().map(e -> e.getKey() + "=\"" + e.getValue() + "\"").collect(joining(", "));
	}

	private MessageToSend inviteMessage(Call call) {
		var locPort = connection.localPort();
		var from = sipIdentifier(config.getUsername());
		var to = sipIdentifier(call.getDestinationNumber());

		return factory.newMessage("INVITE", to) //
				.add("Call-ID", "%010d@%s", call.callId, locIpAddr) //
				.add("From", prefixCallerNameIfExistent(call, "<%s>".formatted(from)))
				.add("Via", "%s/UDP %s:%d;rport=%d", factory.sipVersion(), locIpAddr, locPort, locPort)
				.add("To", "<" + to + ">") //
				.add("Contact", "\"%s\" <%s:%d;transport=udp>", config.getUsername(), from, locPort) //
		;
	}

	private static String prefixCallerNameIfExistent(Call call, String in) {
		return call.getCallerName() == null ? in : "\"%s\" ".formatted(call.getCallerName()) + in;
	}

	private MessageToSend ackMessage(Call call) {
		Pattern pattern = Pattern.compile("<(.*?)>");
		Matcher matcher = pattern.matcher(call.received.get("To"));
		String ca = matcher.find() ? matcher.group(1) : null;
		return copyFromViaToFromAndLastCallFromLastReceived(call.received, factory.newMessage("ACK", ca));
	}

	private MessageToSend byeMessage(Call call) {
		return copyFromViaToFromAndLastCallFromLastReceived(call.received,
				factory.newMessage("BYE", sipIdentifier(call.getDestinationNumber())));
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

	private String sipIdentifier(String number) {
		return format("sip:%s@%s", number, connection.remoteServerAddress());
	}

	private static String extractValue(String text, String key) {
		return tryExtractValue(text, key)
				.orElseThrow(() -> new IllegalStateException("'" + key + "' not found in '" + text + "'"));

	}

	private static Optional<String> tryExtractValue(String text, String key) {
		Pattern pattern = Pattern.compile(key + "=\"([^\"]+)\"");
		Matcher matcher = pattern.matcher(text);
		return Optional.ofNullable(matcher.find() ? matcher.group(1) : null);
	}

	private static String hash(String algorithm, String input) {
		try {
			MessageDigest md = MessageDigest.getInstance(algorithm);
			StringBuilder hexString = new StringBuilder();
			for (byte hashByte : md.digest(input.getBytes())) {
				hexString.append(format("%02x", hashByte & 0xFF));
			}
			return hexString.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Optional<InetAddress> localIpAddress() {
		try {
			var ignoreIfStartsWith = List.of("docker", "br-", "veth");
			return list(getNetworkInterfaces()).stream().filter(i -> {
				try {
					return ignoreIfStartsWith.stream().noneMatch(i.getName()::startsWith) && i.isUp() && !i.isLoopback()
							&& !i.isVirtual();
				} catch (SocketException e) {
					return false;
				}
			}).flatMap(i -> list(i.getInetAddresses()).stream())
					.filter(a -> a.isSiteLocalAddress() && !a.isLoopbackAddress()).findFirst();
		} catch (SocketException e) {
			return Optional.empty();
		}
	}

}
