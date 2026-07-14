package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@ExtendWith(KamailioLogDumperExtension.class)
class SipRegistrarIT {

	private static final int KAMAILIO_PORT = 15060;
	private static final String REGISTRAR_HOST = "127.0.0.1";

	@Container
	static GenericContainer<?> kamailio = new GenericContainer<>(
			new ImageFromDockerfile("kamailio-test")
					.withDockerfile(Path.of("docker/Dockerfile").toAbsolutePath())
					.withBuildArg("CACHEBUST", Long.toString(System.nanoTime())))
			.withNetworkMode("host")
			.waitingFor(Wait.forLogMessage(".*Listening on.*", 1));

	@Test
	void callThroughRegistrar(@RegisterCallee RegisteredCallee callee) throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "callee", 10);
		await().atMost(5, TimeUnit.SECONDS)
				.alias("call() should return quickly when callee accepts, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isTrue());
	}

	@Test
	void acceptedThenRemoteBye(
			@RegisterCallee(user = "callee7", behavior = CalleeBehavior.ACCEPT_THEN_BYE) RegisteredCallee callee)
			throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "callee7", 10);
		await().atMost(5, TimeUnit.SECONDS)
				.alias("call() should return quickly when callee accepts, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isTrue());
	}

	@Test
	void acceptedDoesNotSendCancel(@RegisterCallee(user = "calleeNoCancel") RegisteredCallee callee) throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "calleeNoCancel", 10);
		await().atMost(5, TimeUnit.SECONDS)
				.alias("call() should return quickly when callee accepts, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isTrue());
		assertThat(callee.isCancelReceived())
				.as("Callee should NOT receive CANCEL when call was accepted")
				.isFalse();
	}

	@Test
	void acceptedThenTimeoutHangup(@RegisterCallee(user = "callee8") RegisteredCallee callee) throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "callee8", 3);
		await().atMost(5, TimeUnit.SECONDS)
				.alias("call() should return quickly when callee accepts, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isTrue());
	}

	@Test
	void calleeRefuses(
			@RegisterCallee(user = "callee9", behavior = CalleeBehavior.REFUSE) RegisteredCallee callee)
			throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "callee9", 10);
		await().atMost(5, TimeUnit.SECONDS)
				.alias("call() should return quickly when callee refuses, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isFalse());
	}

	@Test
	void noRouteReturnsNotFound() throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "unregistered", 5);
		assertThat(callService.call()).isFalse();
		assertThat(callService.getReason()).contains("Not Found");
	}

	@Test
	void timeoutNoAnswer(
			@RegisterCallee(user = "callee11", behavior = CalleeBehavior.IGNORE) RegisteredCallee callee)
			throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "callee11", 3);
		assertThat(callService.call()).isFalse();
	}

	@Test
	void timeoutAfterProvisional(@RegisterCallee(user = "callee12",
			behavior = CalleeBehavior.PROVISIONAL_183) RegisteredCallee callee) throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "callee12", 3);
		assertThat(callService.call()).isFalse();
		assertThat(callService.getReason()).as("Reason should indicate CANCEL, not timeout")
				.isNotEqualTo("Request Timeout");
	}

	@Test
	void timeoutSendsCancel(@RegisterCallee(user = "calleeCancel",
			behavior = CalleeBehavior.IGNORE) RegisteredCallee callee) throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "calleeCancel", 3);
		assertThat(callService.call()).isFalse();
		callee.awaitCancel(5, TimeUnit.SECONDS);
		assertThat(callee.isCancelReceived()).as("Callee should have received CANCEL on timeout").isTrue();
	}

	@Test
	void ringingThenAccept(@RegisterCallee(user = "calleeRinging",
			behavior = CalleeBehavior.RINGING_THEN_ACCEPT) RegisteredCallee callee) throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "calleeRinging", 10);
		await().atMost(8, TimeUnit.SECONDS)
				.alias("call() should return quickly after 180+200, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isTrue());
	}

	@Test
	void ringingThenDecline(@RegisterCallee(user = "calleeRingingDecline",
			behavior = CalleeBehavior.RINGING_THEN_DECLINE) RegisteredCallee callee) throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "calleeRingingDecline", 10);
		await().atMost(8, TimeUnit.SECONDS)
				.alias("call() should return quickly after 180+4xx, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isFalse());
	}

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			s.setReuseAddress(true);
			return s.getLocalPort();
		}
	}

	private CallService createCaller(int port, String destination, int timeoutSeconds) {
		return new CallService(
				REGISTRAR_HOST, KAMAILIO_PORT,
				"caller", "pass",
				destination, null,
				timeoutSeconds, "udp",
				port);
	}

}
