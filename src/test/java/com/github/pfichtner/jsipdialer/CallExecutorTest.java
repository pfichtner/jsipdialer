package com.github.pfichtner.jsipdialer;

import static com.github.pfichtner.jsipdialer.messages.SipStatus.UNAUTHORIZED;
import static com.github.pfichtner.jsipdialer.messages.Statuscode.statuscodeOf;
import static java.util.Collections.emptyList;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Predicate.isEqual;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.github.pfichtner.jsipdialer.messages.MessageFactory;
import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;

@Timeout(value = CallExecutorTest.TIMEOUT_SECONDS, unit = SECONDS)
class CallExecutorTest {

	static final int TIMEOUT_SECONDS = 20;

	ConnectionStub connection = new ConnectionStub();
	SipConfig config = new SipConfig("aSipUser", "secret");
	CallExecutor callExecutor = new CallExecutor(connection, config, new MessageFactory());

	@Test
	void noResponseWillTerminateAfterTimeout() throws Exception {
		Call call = new Call("123", "the callers name", 5);
		callExecutor.execCall(call);

		assertThat(call.statuscode()).isNull();
		var sent = connection.sent();
		assertThat(filterCommands(sent, "INVITE")).hasSize(5);
		assertThat(filterCommands(sent, "BYE")).hasSize(5);
		assertThat(sent).hasSize(10);
	}

	@Test
	void sendsAuthOnUnauthorizedResponse() throws Exception {
		var realm = "XXX";
		var nonce = "YYY";
		var answer = unauthorizedAnswer("realm=\"" + realm + "\", nonce=\"" + nonce + "\"");
		connection.messageReceivedSupplier(() -> answer);

		var call = new Call("123", "the callers name", 2 * TIMEOUT_SECONDS);
		callInBackground(call);

		var expectedValue = """
				Digest \
				username="%s", \
				realm="%s", \
				nonce="%s", \
				uri="sip:%s@%s", \
				response="d7279a9da44929e24abb213421b33f96", \
				algorithm="MD5"\
				""".formatted(config.getUsername(), realm, nonce, call.getDestinationNumber(),
				connection.remoteServerAddress());
		await().forever().until(() -> whereMatches("INVITE", "Authorization", expectedValue));
	}

	@Test
	void doesSendCallernameIfItsPresent() throws Exception {
		var call = new Call("123", "the callers name", 2 * TIMEOUT_SECONDS);
		callInBackground(call);

		var expectedValue = "\"%s\" <sip:%s@%s>".formatted(call.getCallerName(), config.getUsername(),
				connection.remoteServerAddress());
		await().forever().until(() -> whereMatches("INVITE", "From", expectedValue));
	}

	@Test
	void doesNotSendCallernameIfItsNotPresent() throws Exception {
		var call = new Call("123", null, 2 * TIMEOUT_SECONDS);

		callInBackground(call);
		var expectedValue = "<sip:%s@%s>".formatted(config.getUsername(), connection.remoteServerAddress());
		await().forever().until(() -> whereMatches("INVITE", "From", expectedValue));
	}

	private static MessageReceived unauthorizedAnswer(String authString) {
		var status = UNAUTHORIZED;
		return new MessageReceived("SIP/2.0 ", statuscodeOf(status), status.name(),
				Map.of("WWW-Authenticate", authString), emptyList());
	}

	private boolean whereMatches(String command, String key, String expectedValue) {
		return whereCommandIs(command).anyMatch(m -> expectedValue.equals(m.lines().get(key)));
	}

	private Stream<MessageToSend> whereCommandIs(String command) {
		return connection.sent().stream().filter(where(MessageToSend::command, isEqual(command)));
	}

	private static Stream<MessageToSend> filterCommands(List<MessageToSend> sent, String command) {
		return sent.stream().filter(where(MessageToSend::command, isEqual(command)));
	}

	private static Predicate<MessageToSend> where(Function<MessageToSend, String> mapper, Predicate<String> predicate) {
		return m -> predicate.test(mapper.apply(m));
	}

	private void callInBackground(Call call) {
		newSingleThreadExecutor().execute(() -> {
			try {
				callExecutor.execCall(call);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

}
