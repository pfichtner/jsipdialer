package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mjsip.sip.message.SipMessage;
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
	void callThroughRegistrar() throws Exception {
		int calleePort = freePort();

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, "natcallee", (call, p, invite) -> {
			System.err.println("NATCALLEE: received INVITE, accepting");
			System.err.flush();
			call.accept(call.getLocalSessionDescriptor());
		})) {
			callee.awaitRegistration();

			long start = System.currentTimeMillis();
			ProcessResult result = runCaller(callerProcessBuilder("natcallee", 10));
			long elapsed = System.currentTimeMillis() - start;

			assertThat(result.exited()).as("Process should exit within timeout").isTrue();
			assertThat(result.exitValue()).as("Exit code should be 0 (call accepted)%n%s", result.output()).isZero();
			assertThat(elapsed).as("Process should exit quickly when callee accepts, not wait for full timeout")
					.isLessThan(5000);
		}
	}

	@Test
	void acceptedDoesNotSendCancel() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleenocancel";

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, calleeUser, (call, p, invite) -> {
			System.err.println("NATCALLEENOCANCEL: received INVITE, accepting");
			System.err.flush();
			call.accept(call.getLocalSessionDescriptor());
		})) {
			callee.awaitRegistration();

			long start = System.currentTimeMillis();
			ProcessResult result = runCaller(callerProcessBuilder(calleeUser, 10));
			long elapsed = System.currentTimeMillis() - start;

			assertThat(result.exited()).as("Process should exit within timeout").isTrue();
			assertThat(result.exitValue()).as("Exit code should be 0 (call accepted)%n%s", result.output()).isZero();
			assertThat(elapsed).as("Process should exit quickly when callee accepts, not wait for full timeout")
					.isLessThan(5000);
			assertThat(callee.isCancelReceived())
					.as("Callee should NOT receive CANCEL when call was accepted")
					.isFalse();
		}
	}

	@Test
	void calleeRefuses() throws Exception {
		int calleePort = freePort();

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, "natcalleerefuse", (call, p, invite) -> {
			System.err.println("NATCALLEEREFUSE: received INVITE, refusing");
			System.err.flush();
			call.refuse();
		})) {
			callee.awaitRegistration();

			long start = System.currentTimeMillis();
			ProcessResult result = runCaller(callerProcessBuilder("natcalleerefuse", 10));
			long elapsed = System.currentTimeMillis() - start;

			assertThat(result.exited()).as("Process should exit within timeout").isTrue();
			assertThat(result.exitValue()).as("Exit code should be 1 (call refused)%n%s", result.output()).isEqualTo(1);
			assertThat(elapsed).as("Process should exit quickly when callee refuses, not wait for full timeout")
					.isLessThan(5000);
		}
	}

	@Test
	void timeoutNoAnswer() throws Exception {
		int calleePort = freePort();

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, "natcalleetimeout", (call, p, invite) -> {
			System.err.println("NATCALLEETIMEOUT: received INVITE, ignoring");
			System.err.flush();
		})) {
			callee.awaitRegistration();

			ProcessResult result = runCaller(callerProcessBuilder("natcalleetimeout", 3));

			assertThat(result.exited()).as("Process should exit within timeout").isTrue();
			assertThat(result.exitValue()).as("Exit code should be 1 (timeout, no answer)%n%s", result.output()).isEqualTo(1);
		}
	}

	@Test
	void timeoutSendsCancel() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleecancel";

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, calleeUser, (call, p, invite) -> {
			System.err.println("NATCALLEECANCEL: received INVITE, ignoring (waiting for CANCEL)");
			System.err.flush();
		})) {
			callee.awaitRegistration();

			ProcessResult result = runCaller(callerProcessBuilder(calleeUser, 3));

			assertThat(result.exited()).as("Process should exit within timeout").isTrue();
			assertThat(result.exitValue()).as("Exit code should be 1 (timeout, no answer)%n%s", result.output()).isEqualTo(1);
			callee.awaitCancel(5, TimeUnit.SECONDS);
			assertThat(callee.isCancelReceived()).as("Callee should have received CANCEL on timeout").isTrue();
		}
	}

	@Test
	void timeoutAfterProvisional() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleeprov";

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, calleeUser, (call, p, invite) -> {
			System.err.println("CALLEEPROV: received INVITE, sending 183");
			System.err.flush();
			SipMessage resp183 = p.messageFactory().createResponse(invite, 183, "Session Progress", null);
			p.sendMessage(resp183);
		})) {
			callee.awaitRegistration();

			ProcessResult result = runCaller(callerProcessBuilder(calleeUser, 3));

			assertThat(result.exited()).as("Process should exit within timeout").isTrue();
			assertThat(result.exitValue()).as("Exit code should be 1 (timeout after 183)%n%s", result.output()).isEqualTo(1);
		}
	}

	@Test
	void calleeRefusesAfterProvisional() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleerefuseprov";

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, calleeUser, (call, p, invite) -> {
			System.err.println("CALLEEPROVREFUSE: received INVITE, sending 183 then refusing");
			System.err.flush();
			SipMessage resp183 = p.messageFactory().createResponse(invite, 183, "Session Progress", null);
			p.sendMessage(resp183);
			call.refuse();
		})) {
			callee.awaitRegistration();

			ProcessBuilder pb = callerProcessBuilder(calleeUser, 10);

			Process process = pb.start();

			// Drain output in background so the process does not block on a full pipe
			// and so we can await the INVITE arriving at the callee.
			ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
			Thread outReader = new Thread(() -> {
				try (InputStream in = process.getInputStream()) {
					in.transferTo(outBuf);
				} catch (IOException ignored) {
				}
			});
			outReader.start();

			// The caller was just started; wait until its INVITE actually reaches
			// the callee (which then sends 183 and refuses).
			callee.awaitInvite(10, TimeUnit.SECONDS);

			boolean exited = process.waitFor(30, TimeUnit.SECONDS);
			outReader.join();
			String output = outBuf.toString(StandardCharsets.UTF_8);

			assertThat(exited).as("Process should exit within timeout").isTrue();
			assertThat(process.exitValue()).as("Exit code should be 1 (call refused after provisional)%n%s", output)
					.isEqualTo(1);
		}
	}

	@Test
	void ringingThenAccept() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleeringing";

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, calleeUser, (call, p, invite) -> {
			System.err.println("RINGING: received INVITE, sending 180 then accepting after 2s");
			System.err.flush();
			SipMessage resp180 = p.messageFactory().createResponse(invite, 180, "Ringing", null);
			p.sendMessage(resp180);
			new Thread(() -> {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				System.err.println("RINGING: accepting now");
				System.err.flush();
				call.accept(call.getLocalSessionDescriptor());
			}).start();
		})) {
			callee.awaitRegistration();

			long start = System.currentTimeMillis();
			ProcessResult result = runCaller(callerProcessBuilder(calleeUser, 10));
			long elapsed = System.currentTimeMillis() - start;

			assertThat(result.exited()).as("Process should exit within timeout").isTrue();
			assertThat(result.exitValue()).as("Exit code should be 0 (call accepted)%n%s", result.output()).isZero();
			assertThat(elapsed).as("Process should exit quickly after 180+200, not wait for full timeout")
					.isLessThan(8000);
		}
	}

	@Test
	void ringingThenDecline() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleeringingdecline";

		try (RegisteredCallee callee = RegisteredCallee.register(calleePort, calleeUser, (call, p, invite) -> {
			System.err.println("RINGINGDECL: received INVITE, sending 180 then declining after 2s");
			System.err.flush();
			SipMessage resp180 = p.messageFactory().createResponse(invite, 180, "Ringing", null);
			p.sendMessage(resp180);
			new Thread(() -> {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				System.err.println("RINGINGDECL: declining now");
				System.err.flush();
				call.refuse();
			}).start();
		})) {
			callee.awaitRegistration();

			long start = System.currentTimeMillis();
			ProcessResult result = runCaller(callerProcessBuilder(calleeUser, 10));
			long elapsed = System.currentTimeMillis() - start;

			assertThat(result.exited()).as("Process should exit within timeout").isTrue();
			assertThat(result.exitValue()).as("Exit code should be 1 (call declined)%n%s", result.output()).isEqualTo(1);
			assertThat(elapsed).as("Process should exit quickly after 180+4xx, not wait for full timeout")
					.isLessThan(8000);
		}
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

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			s.setReuseAddress(true);
			return s.getLocalPort();
		}
	}
}
