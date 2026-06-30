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

	private volatile boolean success;
	private volatile String reason;

	public CallService(String serverAddress, int serverPort, String username, String password,
			String destinationNumber, String callerName, int timeoutSeconds, String transport) {
		this.serverAddress = serverAddress;
		this.serverPort = serverPort;
		this.username = username;
		this.password = password;
		this.destinationNumber = destinationNumber;
		this.callerName = callerName;
		this.timeoutSeconds = timeoutSeconds;
		this.transport = transport;
	}

	public boolean call() throws Exception {
		SipConfig sipConfig = new SipConfig();
		sipConfig.setTransportProtocols(new String[] { transport });
		sipConfig.setOutboundProxy(new SipURI(serverAddress, serverPort));
		sipConfig.setHostPort(15062);
		sipConfig.normalize();

		SchedulerConfig schedulerConfig = new SchedulerConfig();
		SipProvider sipProvider = new SipProvider(sipConfig, new ConfiguredScheduler(schedulerConfig));

		CountDownLatch latch = new CountDownLatch(1);

		var listener = new CallListenerAdapter() {
			@Override
			public void onCallAccepted(Call call, SdpMessage sdp, SipMessage resp) {
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

		ExtendedCall call = new ExtendedCall(sipProvider, sipUser, listener);

		if (timeoutSeconds > 0) {
			sipProvider.scheduler().schedule(timeoutSeconds * 1000L, call::hangup);
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

		latch.await(5, TimeUnit.MINUTES);

		Thread.sleep(500);
		sipProvider.halt();

		return success;
	}

	public String getReason() {
		return reason;
	}
}
