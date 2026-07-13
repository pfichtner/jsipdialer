package com.github.pfichtner.jsipdialer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mjsip.sdp.SdpMessage;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sdp.MediaDescriptor;
import org.mjsip.sdp.field.ConnectionField;
import org.mjsip.sdp.field.MediaField;
import org.mjsip.sip.call.Call;
import org.mjsip.sip.call.CallListenerAdapter;
import org.mjsip.sip.call.ExtendedCall;
import org.mjsip.sip.call.SipUser;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;

public class CallService {

	private final String serverAddress;
	private final int serverPort;
	private final String username;
	private final String password;
	private final String destinationNumber;
	private final String callerName;
	private final int timeoutSeconds;
	private final String transport;
	private final int localPort;

	private volatile boolean success;
	private volatile boolean callAccepted;
	private volatile String reason;
	private volatile boolean terminated;

	private CallExt call;
	private SipProvider sipProvider;

	public CallService(String serverAddress, int serverPort, String username, String password,
			String destinationNumber, String callerName, int timeoutSeconds, String transport) {
		this(serverAddress, serverPort, username, password, destinationNumber, callerName, timeoutSeconds, transport,
				15062);
	}

	public CallService(String serverAddress, int serverPort, String username, String password,
			String destinationNumber, String callerName, int timeoutSeconds, String transport, int localPort) {
		this.serverAddress = serverAddress;
		this.serverPort = serverPort;
		this.username = username;
		this.password = password;
		this.destinationNumber = destinationNumber;
		this.callerName = callerName;
		this.timeoutSeconds = timeoutSeconds;
		this.transport = transport;
		this.localPort = localPort;
	}

	public boolean call() throws Exception {
		SipConfig sipConfig = new SipConfig();
		sipConfig.setTransportProtocols(new String[] { transport });
		sipConfig.setOutboundProxy(new SipURI(serverAddress, serverPort));
		sipConfig.setHostPort(localPort);
		sipConfig.setViaAddrIPv4("127.0.0.1");
		sipConfig.normalize();

		SchedulerConfig schedulerConfig = new SchedulerConfig();
		sipProvider = new SipProvider(sipConfig, new ConfiguredScheduler(schedulerConfig));

		CountDownLatch latch = new CountDownLatch(1);

		var listener = new CallListenerAdapter() {
			@Override
			public void onCallAccepted(Call call, SdpMessage sdp, SipMessage resp) {
				callAccepted = true;
				success = true;
				CallService.this.reason = "OK";
				latch.countDown();
			}

			@Override
			public void onCallRefused(Call call, String reason, SipMessage resp) {
				success = false;
				CallService.this.reason = reason;
				latch.countDown();
			}

			@Override
			public void onCallRedirected(Call call, String reason, java.util.Vector contactList, SipMessage resp) {
				success = false;
				CallService.this.reason = "Redirected: " + reason;
				latch.countDown();
			}

			@Override
			public void onCallTimeout(Call call) {
				success = false;
				CallService.this.reason = "Request Timeout";
				latch.countDown();
			}

			@Override
			public void onCallCancel(Call call, SipMessage cancel) {
				latch.countDown();
			}

			@Override
			public void onCallBye(Call call, SipMessage bye) {
				latch.countDown();
			}

			@Override
			public void onCallClosed(Call call, SipMessage resp) {
				latch.countDown();
			}
		};

		SipUser sipUser = new SipUser(new NameAddress(new SipURI(username, serverAddress)), username, serverAddress,
				password);

		call = new CallExt(sipProvider, sipUser, listener);

		Thread shutdownHook = new Thread(this::terminateCall, "sip-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		try {
			if (timeoutSeconds > 0) {
				sipProvider.scheduler().schedule(timeoutSeconds * 1000L, this::terminateCall);
			}

			NameAddress callee = new NameAddress(new SipURI(destinationNumber, serverAddress));

			NameAddress caller = callerName != null
					? new NameAddress(callerName, new SipURI(username, serverAddress))
					: new NameAddress(new SipURI(username, serverAddress));

			SdpMessage sdpOffer = SdpMessage.createSdpMessage(username, "0.0.0.0");
			sdpOffer = sdpOffer.addMediaDescriptor(new MediaDescriptor(
					new MediaField("audio", 9, 1, "RTP/AVP", "0"),
					new ConnectionField(ConnectionField.addressType("0.0.0.0"), "0.0.0.0"),
					java.util.Collections.emptyList()));

			call.call(callee, caller, sdpOffer);

			long awaitSeconds = timeoutSeconds > 0 ? timeoutSeconds + 5 : 300;
			latch.await(awaitSeconds, TimeUnit.SECONDS);

			terminateCall();

			return success;
		} finally {
			try {
				Thread.sleep(500);
			} catch (InterruptedException ignored) {
			}
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
			}
			sipProvider.halt();
		}
	}

	private void terminateCall() {
		if (terminated) {
			return;
		}
		terminated = true;
		CallExt c = this.call;
		if (c != null) {
			try {
				c.hangup();
			} catch (Exception ignored) {
			}
		}
	}

	public String getReason() {
		return reason;
	}

	private static class CallExt extends ExtendedCall {
		CallExt(SipProvider sipProvider, SipUser user, CallListenerAdapter listener) {
			super(sipProvider, user, listener);
		}
	}
}
