package com.github.pfichtner.jsipdialer;

import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mjsip.sdp.SdpMessage;
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
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;

record RegisteredCallee(AtomicBoolean registered, AtomicBoolean cancelReceived, AtomicBoolean inviteReceived,
		ExtendedCall call, SipProvider provider) implements AutoCloseable {

	static final int KAMAILIO_PORT = 15060;
	static final String REGISTRAR_HOST = "127.0.0.1";

	@FunctionalInterface
	interface CalleeAction {
		void onInvite(Call call, SipProvider provider, SipMessage invite);
	}

	static RegisteredCallee register(int port, String user, CalleeAction action) {
		SchedulerConfig schedConfig = new SchedulerConfig();
		SipConfig config = new SipConfig();
		config.setTransportProtocols(new String[] { "udp" });
		config.setHostPort(port);
		config.setViaAddrIPv4("127.0.0.1");
		config.setTransactionTimeout(5000);
		config.setForceRport(true);
		config.normalize();

		SipProvider provider = new SipProvider(config, new ConfiguredScheduler(schedConfig));

		AtomicBoolean registered = new AtomicBoolean();
		AtomicBoolean cancelReceived = new AtomicBoolean();
		AtomicBoolean inviteReceived = new AtomicBoolean();

		sendRegister(provider, REGISTRAR_HOST, KAMAILIO_PORT, user, "127.0.0.1", port, registered);

		provider.addPromiscuousListener((p, msg) -> {
			if (msg.isRequest() && msg.isCancel()) {
				cancelReceived.set(true);
			}
		});

		SdpMessage calleeSdp = SdpMessage.createSdpMessage(user, "0.0.0.0");
		ExtendedCall calleeCall = new ExtendedCall(provider,
				new SipUser(
						new NameAddress(new SipURI(user, "127.0.0.1")),
						new NameAddress(new SipURI(user, "127.0.0.1", port))),
				new CallListenerAdapter() {
					@Override
					public void onCallInvite(Call call, NameAddress callee, NameAddress caller,
							SdpMessage sdp, SipMessage invite) {
						inviteReceived.set(true);
						action.onInvite(call, provider, invite);
					}
				});
		calleeCall.setLocalSessionDescriptor(calleeSdp);
		calleeCall.listen();

		return new RegisteredCallee(registered, cancelReceived, inviteReceived, calleeCall, provider);
	}

	void awaitRegistration() {
		await().atMost(5, TimeUnit.SECONDS).untilTrue(registered);
	}

	void awaitCancel(long timeout, TimeUnit unit) {
		await().atMost(timeout, unit).untilTrue(cancelReceived);
	}

	void awaitInvite(long timeout, TimeUnit unit) {
		await().atMost(timeout, unit).untilTrue(inviteReceived);
	}

	boolean isCancelReceived() {
		return cancelReceived.get();
	}

	void hangup() {
		call.hangup();
	}

	void halt() {
		provider.halt();
	}

	@Override
	public void close() {
		hangup();
		halt();
	}

	static void sendRegister(SipProvider provider, String registrarHost, int registrarPort,
			String user, String host, int contactPort, AtomicBoolean registered) {

		SipMessage register = provider.messageFactory().createRegisterRequest(
				new SipURI(registrarHost, registrarPort),
				new NameAddress(new SipURI(user, host)),
				new NameAddress(new SipURI(user, host)),
				new NameAddress(new SipURI(user, host, contactPort)),
				null);
		register.addHeader(new ExpiresHeader(3600), false);

		provider.addPromiscuousListener((p, msg) -> {
			if (msg.isResponse() && msg.getStatusLine() != null
					&& msg.getStatusLine().getCode() == 200) {
				System.err.println("REGISTER: 200 OK received for " + user);
				System.err.flush();
				registered.set(true);
			}
		});

		System.err.println("REGISTER: sending " + user + " to " + registrarHost + ":" + registrarPort);
		System.err.flush();
		provider.sendMessage(register);
	}
}
