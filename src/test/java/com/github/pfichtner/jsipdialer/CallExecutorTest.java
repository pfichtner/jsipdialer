package com.github.pfichtner.jsipdialer;

import static com.github.pfichtner.jsipdialer.messages.Constants.SIP_VERSION;
import static com.github.pfichtner.jsipdialer.messages.SipStatus.*;
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

import com.github.pfichtner.jsipdialer.messages.CSeq;
import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.MessageToSend;
import com.github.pfichtner.jsipdialer.messages.SipStatus;

@Timeout(value = CallExecutorTest.TIMEOUT_SECONDS, unit = SECONDS)
class CallExecutorTest {

	static final int TIMEOUT_SECONDS = 20;

	ConnectionStub connection = new ConnectionStub();
	SipConfig config = new SipConfig("aSipUser", "secret");
	CallExecutor callExecutor = new CallExecutor(connection, config);

	@Test
	void noResponseWillTerminateAfterTimeout() throws Exception {
		Call call = new Call("123", "the callers name", 5);
		callExecutor.execCall(call);

		assertThat(call.statuscode()).isNull();
		assertThat(filterCommands(connection.sent(), "INVITE")).hasSize(5);
		assertThat(filterCommands(connection.sent(), "CANCEL")).hasSize(5);
		assertThat(connection.sent()).hasSize(5 + 5);
	}

	@Test
	void withTryingResponseWillTerminateAfterTimeout() throws Exception {
		Call call = new Call("123", "the callers name", 5);

		var toAnswerFromServer = "<sip:abcde@xyz";
		var answers = List.of(answerWithStatus(toAnswerFromServer, TRYING)).iterator();
		connection.messageReceivedSupplier(() -> answers.hasNext() ? answers.next() : null);

		callExecutor.execCall(call);

		assertThat(call.statuscode()).isEqualTo(statuscodeOf(TRYING));
		int countOfAckMessagesSent = 0;
		assertThat(filterCommands(connection.sent(), "INVITE")).hasSize(1);
		assertThat(filterCommands(connection.sent(), "CANCEL")).hasSize(5);
		assertThat(connection.sent()).hasSize(1 + 5 + countOfAckMessagesSent);
	}

	@Test
	void withOkResponseWillTerminateAfterTimeout() throws Exception {
		Call call = new Call("123", "the callers name", 5);

		var toAnswerFromServer = "<sip:abcde@xyz";
		var answers = List.of(answerWithStatus(toAnswerFromServer, OK)).iterator();
		connection.messageReceivedSupplier(() -> answers.hasNext() ? answers.next() : null);

		callExecutor.execCall(call);

		assertThat(call.statuscode()).isEqualTo(statuscodeOf(OK));
		int countOfAckMessagesSent = 1;
		assertThat(filterCommands(connection.sent(), "INVITE")).hasSize(1);
		assertThat(filterCommands(connection.sent(), "BYE")).hasSize(5);
		assertThat(connection.sent()).hasSize(1 + 5 + countOfAckMessagesSent);
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
				""".formatted(config.getUsername(), realm, nonce, call.destinationNumber(),
				connection.remoteServerAddress());
		await().forever().until(() -> whereMatches("INVITE", "Authorization", expectedValue));
	}

	@Test
	void doesSendCallernameIfItsPresent() throws Exception {
		var call = new Call("123", "the callers name", 2 * TIMEOUT_SECONDS);
		callInBackground(call);

		var expectedValue = "\"%s\" <sip:%s@%s>;tag=%010d".formatted(call.callerName(), config.getUsername(),
				connection.remoteServerAddress(), call.tagId());
		await().forever().until(() -> whereMatches("INVITE", "From", expectedValue));
	}

	@Test
	void doesNotSendCallernameIfItsNotPresent() throws Exception {
		var call = new Call("123", null, 2 * TIMEOUT_SECONDS);

		callInBackground(call);
		var expectedValue = "<sip:%s@%s>;tag=%010d".formatted(config.getUsername(), connection.remoteServerAddress(),
				call.tagId());
		await().forever().until(() -> whereMatches("INVITE", "From", expectedValue));
	}

	private static MessageReceived unauthorizedAnswer(String authString) {
		var status = UNAUTHORIZED;
		return new MessageReceived(SIP_VERSION, statuscodeOf(status), status.name(), CSeq.of(1),
				Map.of("WWW-Authenticate", authString), emptyList());
	}

	private static MessageReceived answerWithStatus(String toAnswerFromServer, SipStatus status) {
		CSeq seq = CSeq.of(1);
		return new MessageReceived(
				SIP_VERSION, statuscodeOf(status), status.name(), seq, Map.of("From", "<sip:someFrom>", "Via",
						"someVia", "Call-ID", "someCallId", "To", toAnswerFromServer, "Contact", "<sip:someContact>"),
				emptyList());
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
