package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
class SipRegistrarIT {

	private static final int CALLEE_PORT = 15570;
	private static final int CALLER_PORT = 15572;
	private static final int KAMAILIO_PORT = 15060;

	@Container
	static GenericContainer<?> kamailio = new GenericContainer<>(
			new ImageFromDockerfile("kamailio-test")
					.withDockerfile(Path.of("docker/Dockerfile").toAbsolutePath())
					.withBuildArg("CACHEBUST", Long.toString(System.nanoTime())))
			.withNetworkMode("host")
			.waitingFor(Wait.forLogMessage(".*Listening on.*", 1));

	@Test
	void callThroughRegistrar() throws Exception {
		String registrarHost = "127.0.0.1";
		int registrarPort = KAMAILIO_PORT;

		SchedulerConfig schedConfig = new SchedulerConfig();

		// --- Callee: register with Kamailio, then listen for INVITEs ---
		SipConfig calleeConfig = new SipConfig();
		calleeConfig.setTransportProtocols(new String[] { "udp" });
		calleeConfig.setHostPort(CALLEE_PORT);
		calleeConfig.setViaAddrIPv4("127.0.0.1");
		calleeConfig.setTransactionTimeout(5000);
		calleeConfig.setForceRport(true);
		calleeConfig.normalize();

		SipProvider calleeProvider = new SipProvider(calleeConfig, new ConfiguredScheduler(schedConfig));

		AtomicBoolean registered = new AtomicBoolean();
		sendRegister(calleeProvider, registrarHost, registrarPort, "callee", "127.0.0.1", CALLEE_PORT, registered);
		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);

		SdpMessage calleeSdp = SdpMessage.createSdpMessage("callee", "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(calleeProvider,
				new SipUser(
						new NameAddress(new SipURI("callee", "127.0.0.1")),
						new NameAddress(new SipURI("callee", "127.0.0.1", CALLEE_PORT))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						System.err.println("CALLEE: received INVITE from " + caller);
						System.err.flush();
						call.accept(call.getLocalSessionDescriptor());
						System.err.println("CALLEE: accepted");
						System.err.flush();
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		// --- Caller: call through Kamailio using CallService ---
		CallService callService = new CallService(
				registrarHost, registrarPort,
				"caller", "pass",
				"callee", null,
				10, "udp",
				CALLER_PORT);

		boolean success = callService.call();

		assertThat(success).isTrue();

		// --- Cleanup ---
		calleeCall.hangup();
		calleeProvider.halt();
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
					System.err.println("REGISTER: 200 OK received");
					System.err.flush();
					registered.set(true);
				}
			}
		});

		System.err.println("REGISTER: sending to " + registrarHost + ":" + registrarPort);
		System.err.flush();
		provider.sendMessage(register);
	}
}
