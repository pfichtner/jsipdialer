package com.github.pfichtner.jsipdialer;

import static com.github.pfichtner.jsipdialer.messages.SipStatus.UNAUTHORIZED;
import static java.util.Collections.emptyList;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.function.Predicate.isEqual;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.github.pfichtner.jsipdialer.messages.MessageFactory;
import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;
import com.github.pfichtner.jsipdialer.messages.Statuscode;

@Timeout(CallExecutorTest.TIMEOUT)
class CallExecutorTest {

	static final int TIMEOUT = 60;

	@Test
	void noResponseWillTerminateAfterTimeout() throws Exception {
		ConnectionStub connection = new ConnectionStub();
		CallExecutor callExecutor = new CallExecutor(connection, new SipConfig("aSipUser", "secret"),
				new MessageFactory());
		Call call = new Call("123", "theCallersName", 5);

		callExecutor.execCall(call);

		assertNull(call.statuscode());
		var sent = connection.sent();
		assertEquals(5L, count(sent, "INVITE"));
		assertEquals(5L, count(sent, "BYE"));
		assertEquals(10, sent.size());
	}

	@Test
	void sendsAuthOnUnauthorizedResponse() throws Exception {
		ConnectionStub connection = new ConnectionStub() {
			@Override
			public MessageReceived receive() throws IOException {
				var status = UNAUTHORIZED;
				var data = Map.of("WWW-Authenticate", "realm=\"XXX\", nonce=\"YYY\"");
				return new MessageReceived("SIP/2.0 ", new Statuscode(status.value()), status.name(), data, emptyList());
			}
		};
		CallExecutor callExecutor = new CallExecutor(connection, new SipConfig("aSipUser", "secret"),
				new MessageFactory());
		Call call = new Call("123", "theCallersName", 2 * TIMEOUT);

		callInBackground(callExecutor, call);
		String key = "Authorization";
		String expectedValue = "Digest " //
				+ "username=\"aSipUser\", " //
				+ "realm=\"XXX\", " //
				+ "nonce=\"YYY\", " //
				+ "uri=\"sip:123@" + connection.remoteServerAddress() + "\", " //
				+ "response=\"d7279a9da44929e24abb213421b33f96\", " //
				+ "algorithm=\"MD5\"" //
		;
		await().until(() -> connection.sent().stream().anyMatch(m -> expectedValue.equals(m.lines().get(key))));
	}

	private static void callInBackground(CallExecutor callExecutor, Call call) {
		newSingleThreadExecutor().execute(() -> {
			try {
				callExecutor.execCall(call);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static long count(List<MessageToSend> sent, String command) {
		return sent.stream().map(MessageToSend::command).filter(isEqual(command)).count();
	}

}
