package com.github.pfichtner.jsipdialer;

import static com.github.pfichtner.jsipdialer.messages.SipStatus.UNAUTHORIZED;
import static java.util.Collections.emptyList;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Predicate.isEqual;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.github.pfichtner.jsipdialer.messages.MessageFactory;
import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;
import com.github.pfichtner.jsipdialer.messages.Statuscode;

@Timeout(value = CallExecutorTest.TIMEOUT_SECONDS, unit = SECONDS)
class CallExecutorTest {

	static final int TIMEOUT_SECONDS = 60;

	SipConfig config = new SipConfig("aSipUser", "secret");

	@Test
	void noResponseWillTerminateAfterTimeout() throws Exception {
		ConnectionStub connection = new ConnectionStub();
		CallExecutor callExecutor = new CallExecutor(connection, config, new MessageFactory());
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
		String realm = "XXX";
		String nonce = "YYY";
		ConnectionStub connection = new ConnectionStub() {
			@Override
			public MessageReceived receive() throws IOException {
				var status = UNAUTHORIZED;
				var data = Map.of("WWW-Authenticate", "realm=\"" + realm + "\", nonce=\"" + nonce + "\"");
				return new MessageReceived("SIP/2.0 ", new Statuscode(status.value()), status.name(), data,
						emptyList());
			}
		};
		CallExecutor callExecutor = new CallExecutor(connection, config, new MessageFactory());
		Call call = new Call("123", "theCallersName", 2 * TIMEOUT_SECONDS);

		callInBackground(callExecutor, call);
		String key = "Authorization";
		String expectedValue = """
				Digest \
				username="%s", \
				realm="%s", \
				nonce="%s", \
				uri="sip:%s@%s", \
				response="d7279a9da44929e24abb213421b33f96", \
				algorithm="MD5"\
				""".formatted(config.getUsername(), realm, nonce, call.getDestinationNumber(),
				connection.remoteServerAddress());
		await().forever()
				.until(() -> connection.sent().stream().filter(where(MessageToSend::command, isEqual("INVITE")))
						.anyMatch(m -> expectedValue.equals(m.lines().get(key))));
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
		return sent.stream().filter(where(MessageToSend::command, isEqual(command))).count();
	}

	private static Predicate<MessageToSend> where(Function<MessageToSend, String> mapper, Predicate<String> predicate) {
		return m -> predicate.test(mapper.apply(m));
	}

}
