package com.github.pfichtner.jsipdialer;

import static com.github.pfichtner.jsipdialer.Call.State.CALL_ACTIVE;
import static com.github.pfichtner.jsipdialer.Call.State.INIT;
import static com.github.pfichtner.jsipdialer.Call.State.INVITE_TRYING;
import static com.github.pfichtner.jsipdialer.Call.State.TERMINATED;
import static com.github.pfichtner.jsipdialer.messages.MessageToSend.newMessage;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.BUSY_HERE;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.CALL_DOES_NOT_EXIST;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.DECLINE;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.REQUEST_CANCELLED;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.REQUEST_PENDING;
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

import com.github.pfichtner.jsipdialer.messages.Constants;
import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;

public class CallExecutor {

	private static final Logger logger = Logger.getLogger(CallExecutor.class.getName());
	private static final Pattern quoteedPattern = Pattern.compile("<(.*?)>");

	private final String locIpAddr = localIpAddress().map(InetAddress::getHostAddress).orElse("0.0.0.0");

	private final Connection connection;
	private final SipConfig config;

	public CallExecutor(Connection connection, SipConfig config) {
		this.connection = connection;
		this.config = config;
	}

	public void execCall(Call call) throws Exception {
		MessageToSend inviteMessage = null;
		MessageReceived tryingMessage = null;
		MessageReceived okForInviteMessage = null;
		while (!call.stateIs(TERMINATED) && !call.shouldGiveUp()) {
			if ((call.stateIs(INIT) || call.stateIs(INVITE_TRYING)) && call.shouldTryInvite()) {
				call.increaseInvites();
				connection.send(inviteMessage = inviteMessage(call));
				call.increaeSeq();
				call.state(INVITE_TRYING);
			}

			if (call.shouldTryTerminate()) {
				call.increaseTerminate();
				if (call.stateIs(INVITE_TRYING)) {
					connection.send(cancelMessage(call, inviteMessage, tryingMessage));
				} else if (call.stateIs(CALL_ACTIVE)) {
					connection.send(byeMessage(call, inviteMessage, okForInviteMessage));
				}
			}

			MessageReceived next = connection.receive();
			if (next == null) {
				continue;
			}

			call.received(next);
			if (call.statuscode().is1xx()) {
				if (call.stateIs(INVITE_TRYING)) {
					tryingMessage = call.received();
				}
			} else if (call.statuscode().is2xx()) {
				if (call.stateIs(INVITE_TRYING)) {
					okForInviteMessage = call.received();
					call.state(CALL_ACTIVE);
				} else if (call.stateIs(CALL_ACTIVE)) {
					call.state(TERMINATED);
				}
				connection.send(ackMessage(call, inviteMessage));
			}

			if (call.statuscode().isOneOf(TRYING, REQUEST_PENDING)) {
				connection.send(ackMessage(call, inviteMessage));
			} else if (call.statuscode().isOneOf(BUSY_HERE, DECLINE, REQUEST_CANCELLED)) {
				call.state(TERMINATED);
				connection.send(ackMessage(call, inviteMessage));
			} else if (call.statuscode().is(CALL_DOES_NOT_EXIST)) {
				logger.log(SEVERE, "Error on call handling of " + call.received());
				call.state(TERMINATED);
			} else if (call.stateIs(INVITE_TRYING) && call.statuscode().isUnauthorized()
					&& call.shouldTryInviteWithAuth()) {
				call.increaseInvitesWithAuth();
				connection.send(inviteMessage = addAuthorization(call, inviteMessage(call)));
				call.increaeSeq();
			}
		}
	}

	private MessageToSend addAuthorization(Call call, MessageToSend inviteMessage) {
		var wwwAuthenticate = call.received().get("WWW-Authenticate");
		var realm = extractValue(wwwAuthenticate, "realm");
		var nonce = extractValue(wwwAuthenticate, "nonce");
		var algorithm = tryExtractValue(wwwAuthenticate, "algorithm").orElse("MD5");
		return inviteMessage.add("Authorization", digest(call, realm, nonce, algorithm));
	}

	private String digest(Call call, String realm, String nonce, String algorithm) {
		var hash1 = hash(algorithm, format("%s:%s:%s", config.getUsername(), realm, config.getPassword()));
		var hash2 = hash(algorithm,
				format("INVITE:sip:%s@%s", call.destinationNumber(), connection.remoteServerAddress()));
		var entries = new LinkedHashMap<String, String>();
		entries.put("username", config.getUsername());
		entries.put("realm", realm);
		entries.put("nonce", nonce);
		entries.put("uri", sipIdentifier(call.destinationNumber()));
		entries.put("response", hash(algorithm, format("%s:%s:%s", hash1, nonce, hash2)));
		entries.put("algorithm", algorithm);
		return "Digest "
				+ entries.entrySet().stream().map(e -> e.getKey() + "=\"" + e.getValue() + "\"").collect(joining(", "));
	}

	private MessageToSend inviteMessage(Call call) {
		var locPort = connection.localPort();
		var from = sipIdentifier(config.getUsername());
		var to = sipIdentifier(call.destinationNumber());

		return newMessage(call.cSeq(), "INVITE", to) //
				.add("Call-ID", "%010d@%s", call.callId(), locIpAddr) //
				.add("From", prefixCallerNameIfExistent(call, "<%s>;tag=%010d".formatted(from, call.tagId())))
				.add("Via", "%s/UDP %s:%d;rport=%d", Constants.SIP_VERSION, locIpAddr, locPort, locPort)
				.add("To", "<%s>".formatted(to)) //
				.add("Contact", "\"%s\" <%s:%d;transport=udp>", config.getUsername(), from, locPort) //
		;
	}

	private static String prefixCallerNameIfExistent(Call call, String in) {
		return call.callerName() == null ? in : "\"%s\" ".formatted(call.callerName()) + in;
	}

	private MessageToSend ackMessage(Call call, MessageToSend inviteMessage) {
		return copyFromViaToFromAndCallIdFromInvite(inviteMessage,
				newMessage(call.cSeq(), "ACK", unqouted(call.received(), "To"))).withCseq(call.received().cSeq());
	}

	private MessageToSend byeMessage(Call call, MessageToSend inviteMessage, MessageReceived okMessage) {
		var withCSeqFromInvite = copyFromViaToFromAndCallIdFromInvite(inviteMessage,
				newMessage(call.cSeq(), "BYE", unqouted(okMessage, "Contact"))).withCseq(inviteMessage.cSeq());
		return copy(okMessage, copy(okMessage, withCSeqFromInvite, "From", "To"));
	}

	private MessageToSend cancelMessage(Call call, MessageToSend inviteMessage, MessageReceived tryingMessage) {
		var cancelMessage = copyFromViaToFromAndCallIdFromInvite(inviteMessage,
				newMessage(call.cSeq(), "CANCEL", sipIdentifier(call.destinationNumber())));
		return tryingMessage == null ? cancelMessage : cancelMessage.withCseq(tryingMessage.cSeq());
	}

	private static String unqouted(MessageReceived message, String key) {
		var value = message.get(key);
		var matcher = quoteedPattern.matcher(value);
		return matcher.find() ? matcher.group(1) : value;
	}

	private MessageToSend copy(MessageReceived source, MessageToSend target, String... keys) {
		MessageToSend result = target;
		for (String key : keys) {
			result = result.add(key, source.get(key));
		}
		return result;
	}

	private static MessageToSend copyFromViaToFromAndCallIdFromInvite(MessageToSend source, MessageToSend target) {
		return target //
				.addCopied("From", source) //
				.addCopied("Via", source) //
				.addCopied("To", source) //
				.addCopied("Call-ID", source) //
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
