package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mjsip.sdp.SdpMessage;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.call.Call;
import org.mjsip.sip.call.CallListenerAdapter;
import org.mjsip.sip.call.ExtendedCall;
import org.mjsip.sip.call.SipUser;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
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

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	@AfterEach
	void shutdownExecutor() throws InterruptedException {
		executor.shutdownNow();
		executor.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Container
	static GenericContainer<?> kamailio = new GenericContainer<>(
			new ImageFromDockerfile("kamailio-test")
					.withDockerfile(Path.of("docker/Dockerfile").toAbsolutePath())
					.withBuildArg("CACHEBUST", Long.toString(System.nanoTime())))
			.withNetworkMode("host")
			.waitingFor(Wait.forLogMessage(".*Listening on.*", 1));

	@Test
	void callThroughRegistrar() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();

		try (RegisteredCallee callee = RegisteredCallee.registerAndAwait(calleePort, "callee", (call, invite, respond) -> {
			System.err.println("CALLEE: received INVITE, accepting");
			System.err.flush();
			call.accept(call.getLocalSessionDescriptor());
		})) {

			CallService callService = createCaller(callerPort, "callee", 10);
			await().atMost(5, TimeUnit.SECONDS)
					.alias("call() should return quickly when callee accepts, not wait for timeout")
					.untilAsserted(() -> assertThat(callService.call()).isTrue());
		}
	}

	@Test
	void acceptedThenRemoteBye() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();

		try (RegisteredCallee callee = RegisteredCallee.registerAndAwait(calleePort, "callee7", (call, invite, respond) -> {
			System.err.println("CALLEE7: received INVITE, accepting then BYE");
			System.err.flush();
			call.accept(call.getLocalSessionDescriptor());
			executor.submit(() -> {
				try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				call.hangup();
			});
		})) {

			CallService callService = createCaller(callerPort, "callee7", 10);
			await().atMost(5, TimeUnit.SECONDS)
					.alias("call() should return quickly when callee accepts, not wait for timeout")
					.untilAsserted(() -> assertThat(callService.call()).isTrue());
		}
	}

	@Test
	void acceptedDoesNotSendCancel() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();
		String calleeUser = "calleeNoCancel";

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

		calleeProvider.addPromiscuousListener((p, msg) -> {
			if (msg.isRequest() && msg.isCancel()) {
				System.err.println("CALLEENOCANCEL: ERROR - received CANCEL after accepting!");
				System.err.flush();
				cancelReceived.set(true);
			}
		});

		RegisteredCallee.sendRegister(calleeProvider, REGISTRAR_HOST, KAMAILIO_PORT, calleeUser, "127.0.0.1", calleePort, registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(calleeUser, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI(calleeUser, "127.0.0.1")),
						new NameAddress(new SipURI(calleeUser, "127.0.0.1", calleePort))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("CALLEENOCANCEL: received INVITE, accepting");
						System.err.flush();
						call.accept(call.getLocalSessionDescriptor());
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		CallService callService = createCaller(callerPort, calleeUser, 10);
		await().atMost(5, TimeUnit.SECONDS)
				.alias("call() should return quickly when callee accepts, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isTrue());
		assertThat(cancelReceived)
				.as("Callee should NOT receive CANCEL when call was accepted")
				.isFalse();

		calleeProvider.halt();
	}

	@Test
	void acceptedThenTimeoutHangup() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();

		try (RegisteredCallee callee = RegisteredCallee.registerAndAwait(calleePort, "callee8", (call, invite, respond) -> {
			System.err.println("CALLEE8: received INVITE, accepting (caller will timeout)");
			System.err.flush();
			call.accept(call.getLocalSessionDescriptor());
		})) {

			CallService callService = createCaller(callerPort, "callee8", 3);
			await().atMost(5, TimeUnit.SECONDS)
					.alias("call() should return quickly when callee accepts, not wait for timeout")
					.untilAsserted(() -> assertThat(callService.call()).isTrue());
		}
	}

	@Test
	void calleeRefuses() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();

		try (RegisteredCallee callee = RegisteredCallee.registerAndAwait(calleePort, "callee9", (call, invite, respond) -> {
			System.err.println("CALLEE9: received INVITE, refusing");
			System.err.flush();
			call.refuse();
		})) {

			CallService callService = createCaller(callerPort, "callee9", 10);
			await().atMost(5, TimeUnit.SECONDS)
					.alias("call() should return quickly when callee refuses, not wait for timeout")
					.untilAsserted(() -> assertThat(callService.call()).isFalse());
		}
	}

	@Test
	void noRouteReturnsNotFound() throws Exception {
		int callerPort = freePort();

		CallService callService = createCaller(callerPort, "unregistered", 5);
		assertThat(callService.call()).isFalse();
		assertThat(callService.getReason()).contains("Not Found");
	}

	@Test
	void timeoutNoAnswer() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();

		try (RegisteredCallee callee = RegisteredCallee.registerAndAwait(calleePort, "callee11", (call, invite, respond) -> {
			System.err.println("CALLEE11: received INVITE, ignoring");
			System.err.flush();
		})) {

			CallService callService = createCaller(callerPort, "callee11", 3);
			assertThat(callService.call()).isFalse();
		}
	}

	@Test
	void timeoutAfterProvisional() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();
		String calleeUser = "callee12";

		SchedulerConfig schedConfig = new SchedulerConfig();
		SipConfig config = new SipConfig();
		config.setTransportProtocols(new String[] { "udp" });
		config.setHostPort(calleePort);
		config.setViaAddrIPv4("127.0.0.1");
		config.setTransactionTimeout(5000);
		config.setForceRport(true);
		config.normalize();

		SipProvider calleeProvider = new SipProvider(config, new ConfiguredScheduler(schedConfig));

		AtomicBoolean registered = new AtomicBoolean();
		RegisteredCallee.sendRegister(calleeProvider, REGISTRAR_HOST, KAMAILIO_PORT, calleeUser, "127.0.0.1", calleePort, registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(calleeUser, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI(calleeUser, "127.0.0.1")),
						new NameAddress(new SipURI(calleeUser, "127.0.0.1", calleePort))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("CALLEE12: received INVITE, sending 183 but not answering");
						System.err.flush();
						SipMessage resp183 = calleeProvider.messageFactory()
								.createResponse(invite, 183, "Session Progress", null);
						calleeProvider.sendMessage(resp183);
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		CallService callService = createCaller(callerPort, calleeUser, 3);
		assertThat(callService.call()).isFalse();
		assertThat(callService.getReason()).as("Reason should indicate CANCEL, not timeout")
				.isNotEqualTo("Request Timeout");

		calleeProvider.halt();
	}

	@Test
	void timeoutSendsCancel() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();
		String calleeUser = "calleeCancel";

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
		calleeProvider.addPromiscuousListener((p, msg) -> {
			if (msg.isRequest() && msg.isCancel()) {
				System.err.println("CALLEECANCEL: detected CANCEL via promiscuous listener!");
				System.err.flush();
				cancelReceived.set(true);
			}
		});

		RegisteredCallee.sendRegister(calleeProvider, REGISTRAR_HOST, KAMAILIO_PORT, calleeUser, "127.0.0.1", calleePort, registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(calleeUser, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI(calleeUser, "127.0.0.1")),
						new NameAddress(new SipURI(calleeUser, "127.0.0.1", calleePort))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("CALLEECANCEL: received INVITE, ignoring (waiting for CANCEL)");
						System.err.flush();
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		CallService callService = createCaller(callerPort, calleeUser, 3);
		assertThat(callService.call()).isFalse();
		await().atMost(5, TimeUnit.SECONDS).untilTrue(cancelReceived);
		assertThat(cancelReceived).as("Callee should have received CANCEL on timeout").isTrue();

		calleeProvider.halt();
	}

	@Test
	void ringingThenAccept() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();
		String calleeUser = "calleeRinging";

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

		RegisteredCallee.sendRegister(calleeProvider, REGISTRAR_HOST, KAMAILIO_PORT, calleeUser, "127.0.0.1", calleePort, registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(calleeUser, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI(calleeUser, "127.0.0.1")),
						new NameAddress(new SipURI(calleeUser, "127.0.0.1", calleePort))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("RINGING: received INVITE, sending 180 Ringing then accepting after 2s");
						System.err.flush();
						SipMessage resp180 = calleeProvider.messageFactory()
								.createResponse(invite, 180, "Ringing", null);
						calleeProvider.sendMessage(resp180);
						executor.submit(() -> {
							try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
							System.err.println("RINGING: accepting now");
							System.err.flush();
							call.accept(call.getLocalSessionDescriptor());
						});
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		CallService callService = createCaller(callerPort, calleeUser, 10);
		await().atMost(8, TimeUnit.SECONDS)
				.alias("call() should return quickly after 180+200, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isTrue());

		calleeProvider.halt();
	}

	@Test
	void ringingThenDecline() throws Exception {
		int calleePort = freePort();
		int callerPort = freePort();
		String calleeUser = "calleeRingingDecline";

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

		RegisteredCallee.sendRegister(calleeProvider, REGISTRAR_HOST, KAMAILIO_PORT, calleeUser, "127.0.0.1", calleePort, registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(calleeUser, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI(calleeUser, "127.0.0.1")),
						new NameAddress(new SipURI(calleeUser, "127.0.0.1", calleePort))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("RINGINGDECL: received INVITE, sending 180 Ringing then declining after 2s");
						System.err.flush();
						SipMessage resp180 = calleeProvider.messageFactory()
								.createResponse(invite, 180, "Ringing", null);
						calleeProvider.sendMessage(resp180);
						executor.submit(() -> {
							try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
							System.err.println("RINGINGDECL: declining now");
							System.err.flush();
							call.refuse();
						});
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		CallService callService = createCaller(callerPort, calleeUser, 10);
		await().atMost(8, TimeUnit.SECONDS)
				.alias("call() should return quickly after 180+4xx, not wait for timeout")
				.untilAsserted(() -> assertThat(callService.call()).isFalse());

		calleeProvider.halt();
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
