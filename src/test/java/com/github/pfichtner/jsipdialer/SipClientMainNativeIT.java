package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@EnabledIf(value = "nativeBinaryExecutable", disabledReason = "native binary not found or not executable")
@ExtendWith(KamailioLogDumperExtension.class)
class SipClientMainNativeIT {

	private static final int KAMAILIO_PORT = 15060;
	private static final String REGISTRAR_HOST = "127.0.0.1";
	private static final Path NATIVE_BINARY = Paths.get("target", "jsipdialer");

	@Container
	static GenericContainer<?> kamailio = new GenericContainer<>(
			new ImageFromDockerfile("kamailio-test-native")
					.withDockerfile(Path.of("docker/Dockerfile").toAbsolutePath())
					.withBuildArg("CACHEBUST", Long.toString(System.nanoTime())))
			.withNetworkMode("host")
			.waitingFor(Wait.forLogMessage(".*Listening on.*", 1));

	private static boolean nativeBinaryExecutable() {
		return Files.isExecutable(NATIVE_BINARY);
	}

	@Test
	void callThroughRegistrar(@RegisterCallee(user = "natcallee") RegisteredCallee callee) throws Exception {
		await().atMost(5, TimeUnit.SECONDS)
				.alias("Process should exit quickly when callee accepts, not wait for full timeout")
				.untilAsserted(() -> {
					ProcessResult result = runCaller(callerProcessBuilder("natcallee", 10));
					assertThat(result.exited()).as("Process should exit within timeout").isTrue();
					assertThat(result.exitValue()).as("Exit code should be 0 (call accepted)%n%s", result.output()).isZero();
				});
	}

	@Test
	void acceptedDoesNotSendCancel(@RegisterCallee(user = "natcalleenocancel") RegisteredCallee callee) throws Exception {
		await().atMost(5, TimeUnit.SECONDS)
				.alias("Process should exit quickly when callee accepts, not wait for full timeout")
				.untilAsserted(() -> {
					ProcessResult result = runCaller(callerProcessBuilder("natcalleenocancel", 10));
					assertThat(result.exited()).as("Process should exit within timeout").isTrue();
					assertThat(result.exitValue()).as("Exit code should be 0 (call accepted)%n%s", result.output()).isZero();
					assertThat(callee.isCancelReceived())
							.as("Callee should NOT receive CANCEL when call was accepted")
							.isFalse();
				});
	}

	@Test
	void calleeRefuses(
			@RegisterCallee(user = "natcalleerefuse", behavior = CalleeBehavior.REFUSE) RegisteredCallee callee)
			throws Exception {
		await().atMost(5, TimeUnit.SECONDS)
				.alias("Process should exit quickly when callee refuses, not wait for full timeout")
				.untilAsserted(() -> {
					ProcessResult result = runCaller(callerProcessBuilder("natcalleerefuse", 10));
					assertThat(result.exited()).as("Process should exit within timeout").isTrue();
					assertThat(result.exitValue()).as("Exit code should be 1 (call refused)%n%s", result.output()).isEqualTo(1);
				});
	}

	@Test
	void timeoutNoAnswer(
			@RegisterCallee(user = "natcalleetimeout", behavior = CalleeBehavior.IGNORE) RegisteredCallee callee)
			throws Exception {
		ProcessResult result = runCaller(callerProcessBuilder("natcalleetimeout", 3));

		assertThat(result.exited()).as("Process should exit within timeout").isTrue();
		assertThat(result.exitValue()).as("Exit code should be 1 (timeout, no answer)%n%s", result.output()).isEqualTo(1);
	}

	@Test
	void timeoutSendsCancel(@RegisterCallee(user = "natcalleecancel",
			behavior = CalleeBehavior.IGNORE) RegisteredCallee callee) throws Exception {
		ProcessResult result = runCaller(callerProcessBuilder("natcalleecancel", 3));

		assertThat(result.exited()).as("Process should exit within timeout").isTrue();
		assertThat(result.exitValue()).as("Exit code should be 1 (timeout, no answer)%n%s", result.output()).isEqualTo(1);
		callee.awaitCancel(5, TimeUnit.SECONDS);
		assertThat(callee.isCancelReceived()).as("Callee should have received CANCEL on timeout").isTrue();
	}

	@Test
	void timeoutAfterProvisional(@RegisterCallee(user = "natcalleeprov",
			behavior = CalleeBehavior.PROVISIONAL_183) RegisteredCallee callee) throws Exception {
		ProcessResult result = runCaller(callerProcessBuilder("natcalleeprov", 3));

		assertThat(result.exited()).as("Process should exit within timeout").isTrue();
		assertThat(result.exitValue()).as("Exit code should be 1 (timeout after 183)%n%s", result.output()).isEqualTo(1);
	}

	@Test
	void calleeRefusesAfterProvisional(@RegisterCallee(user = "natcalleerefuseprov",
			behavior = CalleeBehavior.PROVISIONAL_183_THEN_REFUSE) RegisteredCallee callee)
			throws Exception {
		ProcessBuilder pb = callerProcessBuilder("natcalleerefuseprov", 10);

		Process process = pb.start();

		// Wait until the INVITE actually reaches the callee (which then
		// sends 183 and refuses), then drain output and wait for exit.
		callee.awaitInvite(10, TimeUnit.SECONDS);

		String output = new String(process.getInputStream().readAllBytes());
		boolean exited = process.waitFor(30, TimeUnit.SECONDS);

		assertThat(exited).as("Process should exit within timeout").isTrue();
		assertThat(process.exitValue()).as("Exit code should be 1 (call refused after provisional)%n%s", output)
				.isEqualTo(1);
	}

	@Test
	void ringingThenAccept(@RegisterCallee(user = "natcalleeringing",
			behavior = CalleeBehavior.RINGING_THEN_ACCEPT) RegisteredCallee callee) throws Exception {
		await().atMost(8, TimeUnit.SECONDS)
				.alias("Process should exit quickly after 180+200, not wait for full timeout")
				.untilAsserted(() -> {
					ProcessResult result = runCaller(callerProcessBuilder("natcalleeringing", 10));
					assertThat(result.exited()).as("Process should exit within timeout").isTrue();
					assertThat(result.exitValue()).as("Exit code should be 0 (call accepted)%n%s", result.output()).isZero();
				});
	}

	@Test
	void ringingThenDecline(@RegisterCallee(user = "natcalleeringingdecline",
			behavior = CalleeBehavior.RINGING_THEN_DECLINE) RegisteredCallee callee) throws Exception {
		await().atMost(8, TimeUnit.SECONDS)
				.alias("Process should exit quickly after 180+4xx, not wait for full timeout")
				.untilAsserted(() -> {
					ProcessResult result = runCaller(callerProcessBuilder("natcalleeringingdecline", 10));
					assertThat(result.exited()).as("Process should exit within timeout").isTrue();
					assertThat(result.exitValue()).as("Exit code should be 1 (call declined)%n%s", result.output()).isEqualTo(1);
				});
	}

	private static ProcessBuilder callerProcessBuilder(String destinationNumber, int timeout) {
		return new ProcessBuilder(
				NATIVE_BINARY.toAbsolutePath().toString(),
				"-" + SipClientMain.SIP_SERVER_ADDRESS, REGISTRAR_HOST,
				"-" + SipClientMain.SIP_SERVER_PORT, String.valueOf(KAMAILIO_PORT),
				"-" + SipClientMain.USERNAME, "natcaller",
				"-" + SipClientMain.PASSWORD, "pass",
				"-" + SipClientMain.DESTINATION_NUMBER, destinationNumber,
				"-" + SipClientMain.TIMEOUT, String.valueOf(timeout))
				.redirectErrorStream(true);
	}

	private static ProcessResult runCaller(ProcessBuilder pb) throws IOException, InterruptedException {
		Process process = pb.start();
		String output = new String(process.getInputStream().readAllBytes());
		boolean exited = process.waitFor(30, TimeUnit.SECONDS);
		return new ProcessResult(exited, exited ? process.exitValue() : -1, output);
	}

	private record ProcessResult(boolean exited, int exitValue, String output) {
	}

}
