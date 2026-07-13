package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mjsip.sdp.SdpMessage;
import org.mjsip.sip.address.GenericURI;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.call.Call;
import org.mjsip.sip.call.CallListenerAdapter;
import org.mjsip.sip.call.ExtendedCall;
import org.mjsip.sip.call.SipUser;
import org.mjsip.sip.header.ExpiresHeader;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipProviderListener;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
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

		AtomicBoolean registered = new AtomicBoolean();
		RegisteredCallee callee = registerCallee(calleePort, "natcallee", registered, call -> {
			System.err.println("NATCALLEE: received INVITE, accepting");
			System.err.flush();
			call.accept(call.getLocalSessionDescriptor());
		});
		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		ProcessResult result = runCaller(callerProcessBuilder("natcallee", 10));

		assertThat(result.exited()).as("Process should exit within timeout").isTrue();
		assertThat(result.exitValue()).as("Exit code should be 0 (call accepted)%n%s", result.output()).isZero();

		callee.hangup();
		callee.halt();
	}

	@Test
	void calleeRefuses() throws Exception {
		int calleePort = freePort();

		AtomicBoolean registered = new AtomicBoolean();
		RegisteredCallee callee = registerCallee(calleePort, "natcalleerefuse", registered, call -> {
			System.err.println("NATCALLEEREFUSE: received INVITE, refusing");
			System.err.flush();
			call.refuse();
		});
		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		ProcessResult result = runCaller(callerProcessBuilder("natcalleerefuse", 10));

		assertThat(result.exited()).as("Process should exit within timeout").isTrue();
		assertThat(result.exitValue()).as("Exit code should be 1 (call refused)%n%s", result.output()).isEqualTo(1);

		callee.halt();
	}

	@Test
	void timeoutNoAnswer() throws Exception {
		int calleePort = freePort();

		AtomicBoolean registered = new AtomicBoolean();
		RegisteredCallee callee = registerCallee(calleePort, "natcalleetimeout", registered, call -> {
			System.err.println("NATCALLEETIMEOUT: received INVITE, ignoring");
			System.err.flush();
		});
		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		ProcessResult result = runCaller(callerProcessBuilder("natcalleetimeout", 3));

		assertThat(result.exited()).as("Process should exit within timeout").isTrue();
		assertThat(result.exitValue()).as("Exit code should be 1 (timeout, no answer)%n%s", result.output()).isEqualTo(1);

		callee.halt();
	}

	@Test
	void timeoutSendsCancel() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleecancel";

		AtomicBoolean registered = new AtomicBoolean();
		AtomicBoolean cancelReceived = new AtomicBoolean();

		SchedulerConfig schedConfig = new SchedulerConfig();
		SipConfig config = new SipConfig();
		config.setTransportProtocols(new String[] { "udp" });
		config.setHostPort(calleePort);
		config.setViaAddrIPv4("127.0.0.1");
		config.setTransactionTimeout(5000);
		config.setForceRport(true);
		config.normalize();

		SipProvider calleeProvider = new SipProvider(config, new ConfiguredScheduler(schedConfig));

		// Promiscuous listeners fire BEFORE specific listeners (SipProvider line 923 vs 932),
		// so we can detect the CANCEL even though InviteDialog.onReceivedMessage() has the
		// CANCEL handler commented out (responds with 405 Method Not Allowed).
		calleeProvider.addPromiscuousListener(new SipProviderListener() {
			@Override
			public void onReceivedMessage(SipProvider p, SipMessage msg) {
				if (msg.isRequest() && msg.isCancel()) {
					System.err.println("NATCALLEECANCEL: detected CANCEL via promiscuous listener!");
					System.err.flush();
					cancelReceived.set(true);
				}
			}
		});

		sendRegister(calleeProvider, REGISTRAR_HOST, KAMAILIO_PORT, calleeUser, "127.0.0.1", calleePort, registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(calleeUser, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI(calleeUser, "127.0.0.1")),
						new NameAddress(new SipURI(calleeUser, "127.0.0.1", calleePort))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("NATCALLEECANCEL: received INVITE, ignoring (waiting for CANCEL)");
						System.err.flush();
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		ProcessResult result = runCaller(callerProcessBuilder(calleeUser, 3));

		assertThat(result.exited()).as("Process should exit within timeout").isTrue();
		assertThat(result.exitValue()).as("Exit code should be 1 (timeout, no answer)%n%s", result.output()).isEqualTo(1);
		assertThat(cancelReceived).as("Callee should have received CANCEL on timeout").isTrue();

		calleeProvider.halt();
	}

	@Test
	void timeoutAfterProvisional() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleeprov";

		AtomicBoolean registered = new AtomicBoolean();

		SchedulerConfig schedConfig = new SchedulerConfig();
		SipConfig config = new SipConfig();
		config.setTransportProtocols(new String[] { "udp" });
		config.setHostPort(calleePort);
		config.setViaAddrIPv4("127.0.0.1");
		config.setTransactionTimeout(5000);
		config.setForceRport(true);
		config.normalize();

		SipProvider calleeProvider = new SipProvider(config, new ConfiguredScheduler(schedConfig));

		sendRegister(calleeProvider, REGISTRAR_HOST, KAMAILIO_PORT, calleeUser, "127.0.0.1", calleePort, registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(calleeUser, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI(calleeUser, "127.0.0.1")),
						new NameAddress(new SipURI(calleeUser, "127.0.0.1", calleePort))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("CALLEEPROV: received INVITE, sending 183");
						System.err.flush();
						SipMessage resp183 = calleeProvider.messageFactory()
								.createResponse(invite, 183, "Session Progress", null);
						calleeProvider.sendMessage(resp183);
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		ProcessResult result = runCaller(callerProcessBuilder(calleeUser, 3));

		assertThat(result.exited()).as("Process should exit within timeout").isTrue();
		assertThat(result.exitValue()).as("Exit code should be 1 (timeout after 183)%n%s", result.output()).isEqualTo(1);

		calleeProvider.halt();
	}

	@Test
	void calleeRefusesAfterProvisional() throws Exception {
		int calleePort = freePort();
		String calleeUser = "natcalleerefuseprov";

		AtomicBoolean registered = new AtomicBoolean();

		SchedulerConfig schedConfig = new SchedulerConfig();
		SipConfig config = new SipConfig();
		config.setTransportProtocols(new String[] { "udp" });
		config.setHostPort(calleePort);
		config.setViaAddrIPv4("127.0.0.1");
		config.setTransactionTimeout(5000);
		config.setForceRport(true);
		config.normalize();

		SipProvider calleeProvider = new SipProvider(config, new ConfiguredScheduler(schedConfig));

		sendRegister(calleeProvider, REGISTRAR_HOST, KAMAILIO_PORT, calleeUser, "127.0.0.1", calleePort, registered);

		AtomicBoolean inviteReceived = new AtomicBoolean(false);
		SdpMessage calleeSdp = SdpMessage.createSdpMessage(calleeUser, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI(calleeUser, "127.0.0.1")),
						new NameAddress(new SipURI(calleeUser, "127.0.0.1", calleePort))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("CALLEEPROVREFUSE: received INVITE, sending 183 then refusing");
						System.err.flush();
						inviteReceived.set(true);
						SipMessage resp183 = calleeProvider.messageFactory()
								.createResponse(invite, 183, "Session Progress", null);
						calleeProvider.sendMessage(resp183);
						call.refuse();
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

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
		await().atMost(10, TimeUnit.SECONDS).untilTrue(inviteReceived);

		boolean exited = process.waitFor(30, TimeUnit.SECONDS);
		outReader.join();
		String output = outBuf.toString(StandardCharsets.UTF_8);

		assertThat(exited).as("Process should exit within timeout").isTrue();
		assertThat(process.exitValue()).as("Exit code should be 1 (call refused after provisional)%n%s", output).isEqualTo(1);

		calleeProvider.halt();
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

	private RegisteredCallee registerCallee(int port, String user, AtomicBoolean registered, CalleeAction action) {
		SchedulerConfig schedConfig = new SchedulerConfig();
		SipConfig config = new SipConfig();
		config.setTransportProtocols(new String[] { "udp" });
		config.setHostPort(port);
		config.setViaAddrIPv4("127.0.0.1");
		config.setTransactionTimeout(5000);
		config.setForceRport(true);
		config.normalize();

		SipProvider provider = new SipProvider(config, new ConfiguredScheduler(schedConfig));

		sendRegister(provider, REGISTRAR_HOST, KAMAILIO_PORT, user, "127.0.0.1", port, registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(user, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(provider,
				new SipUser(
						new NameAddress(new SipURI(user, "127.0.0.1")),
						new NameAddress(new SipURI(user, "127.0.0.1", port))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						action.onInvite(call);
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		return new RegisteredCallee(calleeCall, provider);
	}

	private void sendRegister(SipProvider provider, String registrarHost, int registrarPort,
			String user, String host, int contactPort, AtomicBoolean registered) {

		GenericURI requestUri = new SipURI(registrarHost, registrarPort);
		NameAddress from = new NameAddress(new SipURI(user, host));
		NameAddress to = new NameAddress(new SipURI(user, host));
		NameAddress contact = new NameAddress(new SipURI(user, host, contactPort));

		SipMessage register = provider.messageFactory().createRegisterRequest(
				requestUri, from, to, contact, null);
		register.addHeader(new ExpiresHeader(3600), false);

		provider.addPromiscuousListener(new SipProviderListener() {
			@Override
			public void onReceivedMessage(SipProvider p, SipMessage msg) {
				if (msg.isResponse() && msg.getStatusLine() != null
						&& msg.getStatusLine().getCode() == 200) {
					System.err.println("REGISTER: 200 OK received for " + user);
					System.err.flush();
					registered.set(true);
				}
			}
		});

		System.err.println("REGISTER: sending " + user + " to " + registrarHost + ":" + registrarPort);
		System.err.flush();
		provider.sendMessage(register);
	}

	@FunctionalInterface
	interface CalleeAction {
		void onInvite(Call call);
	}

	private static class RegisteredCallee {
		private final ExtendedCall call;
		private final SipProvider provider;

		RegisteredCallee(ExtendedCall call, SipProvider provider) {
			this.call = call;
			this.provider = provider;
		}

		void hangup() {
			call.hangup();
		}

		void halt() {
			provider.halt();
		}
	}
}
