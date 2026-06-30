package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mjsip.sdp.MediaDescriptor;
import org.mjsip.sdp.SdpMessage;
import org.mjsip.sdp.field.ConnectionField;
import org.mjsip.sdp.field.MediaField;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.call.Call;
import org.mjsip.sip.call.CallListenerAdapter;
import org.mjsip.sip.call.SipUser;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.UAConfig;
import org.mjsip.ua.registration.RegistrationClient;
import org.mjsip.ua.registration.RegistrationClientListener;

@Tag("integration")
class SipClientMainIT {

	private static final String DOCKER_IMAGE = "kamailio-test";
	private static final String CONTAINER_NAME = "jsipdialer-kamailio";
	private static final int PROXY_PORT = 15060;
	private static final int RECEIVER_PORT = 15061;

	private static SipProvider receiverProvider;
	private static RegistrationClient regClient;

	@BeforeAll
	static void setUpAll() throws Exception {
		stopContainer();
		startContainer();
		receiverProvider = createReceiverProvider();
		registerReceiver();
		Thread.sleep(500);
	}

	@AfterAll
	static void tearDownAll() {
		if (regClient != null)
			regClient.halt();
		if (receiverProvider != null)
			receiverProvider.halt();
		stopContainer();
	}

	@Test
	void callViaRegistrar() throws Exception {
		CountDownLatch callReceived = new CountDownLatch(1);

		var localAddr = new NameAddress(new SipURI("12345", "127.0.0.1", RECEIVER_PORT));
		var receiverUser = new SipUser(localAddr);
		Call receiverCall = new Call(receiverProvider, receiverUser, new CallListenerAdapter() {
			@Override
			public void onCallInvite(Call call, NameAddress callee, NameAddress caller, SdpMessage sdp,
					SipMessage invite) {
				SdpMessage answer = SdpMessage.createSdpMessage("receiver", "0.0.0.0");
				answer = answer.addMediaDescriptor(new MediaDescriptor(
						new MediaField("audio", 9, 1, "RTP/AVP", "0"),
						new ConnectionField(ConnectionField.addressType("0.0.0.0"), "0.0.0.0"),
						java.util.Collections.emptyList()));
				call.accept(answer);
				callReceived.countDown();
			}
		});
		receiverCall.listen();

		try {
			SipClientMain client = new SipClientMain();
			int exitCode = client.doMain(new String[] { "-sipServerAddress", "127.0.0.1", "-sipServerPort",
					String.valueOf(PROXY_PORT), "-sipUsername", "caller", "-sipPassword", "pass",
					"-destinationNumber", "12345", "-timeout", "10" });

			assertThat(exitCode).isZero();
			assertThat(callReceived.await(5, TimeUnit.SECONDS)).isTrue();
		} finally {
			receiverCall.hangup();
		}
	}

	private static void startContainer() throws Exception {
		stopContainer();
		var pb = new ProcessBuilder("docker", "run", "-d", "--name", CONTAINER_NAME,
				"--network", "host", DOCKER_IMAGE);
		pb.inheritIO();
		var process = pb.start();
		process.waitFor(10, TimeUnit.SECONDS);
		Thread.sleep(2000);
	}

	private static void stopContainer() {
		try {
			new ProcessBuilder("docker", "rm", "-f", CONTAINER_NAME)
					.inheritIO().start().waitFor(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			// ignore
		}
	}

	private static SipProvider createReceiverProvider() {
		SipConfig sipConfig = new SipConfig();
		sipConfig.setTransportProtocols(new String[] { "udp" });
		sipConfig.setHostPort(RECEIVER_PORT);
		sipConfig.normalize();

		return new SipProvider(sipConfig, new ConfiguredScheduler(new SchedulerConfig()));
	}

	private static void registerReceiver() throws Exception {
		UAConfig regConfig = new UAConfig();
		regConfig.setSipUser("12345");
		regConfig.setAuthUser("12345");
		regConfig.setAuthPasswd("pass");
		regConfig.setProxy("127.0.0.1");
		regConfig.setRegistrar(new SipURI("127.0.0.1", PROXY_PORT));
		regConfig.setRegister(true);
		regConfig.setExpires(3600);

		CountDownLatch registered = new CountDownLatch(1);
		regClient = new RegistrationClient(receiverProvider, regConfig, new RegistrationClientListener() {
			@Override
			public void onRegistrationSuccess(RegistrationClient registration, NameAddress target, NameAddress contact,
					int expires, int renewTime, String result) {
				registered.countDown();
			}

			@Override
			public void onRegistrationFailure(RegistrationClient registration, NameAddress target, NameAddress contact,
					String result) {
				registered.countDown();
			}
		});
		regClient.register();
		assertThat(registered.await(10, TimeUnit.SECONDS)).isTrue();
	}
}
