package com.github.pfichtner.jsipdialer;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mjsip.sdp.MediaDescriptor;
import org.mjsip.sdp.SdpMessage;
import org.mjsip.sdp.field.ConnectionField;
import org.mjsip.sdp.field.MediaField;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.call.Call;
import org.mjsip.sip.call.CallListenerAdapter;
import org.mjsip.sip.call.ExtendedCall;
import org.mjsip.sip.call.SipUser;
import org.mjsip.sip.header.CSeqHeader;
import org.mjsip.sip.header.MaxForwardsHeader;
import org.mjsip.sip.header.RequestLine;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.message.SipMethods;
import org.mjsip.sip.provider.ConnectionId;
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
	private volatile String reason;
	private volatile boolean terminated;
	private volatile boolean remoteResponded;

	private CountDownLatch latch;
	private SipProvider sipProvider;
	private volatile SipMessage sentInvite;

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
		// Override sendMessage to capture the actual INVITE that goes on the wire.
		// mjSIP's TransactionClient.request() replaces the Via header with a new branch
		// (via SipProvider.pickBranch()) before sending, so the SipMessage held by
		// ExtendedCall/InviteDialog has a different branch than what was actually sent.
		// When we later build a CANCEL on timeout, it must carry the same Via branch as
		// the wire INVITE — otherwise Kamailio rejects it with "RFC3261 transaction
		// matching failed". Capturing the message here is the simplest way to get the
		// correct branch without reflection into Transaction internals.
		sipProvider = new SipProvider(sipConfig, new ConfiguredScheduler(schedulerConfig)) {
			@Override
			public ConnectionId sendMessage(SipMessage msg) {
				if (msg.isInvite()) {
					sentInvite = msg;
				}
				return super.sendMessage(msg);
			}
		};

		CountDownLatch latch = new CountDownLatch(1);
		this.latch = latch;

		// Promiscuous listeners fire BEFORE transaction/dialog listeners.
		// We use this to both log incoming messages (for debugging) and as a
		// fallback to detect final responses to our INVITE when mjSIP's own
		// transaction/dialog listeners don't fire.
		//
		// With some SIP proxies (e.g. FritzBox), the 401 challenge causes a
		// re-INVITE with authentication. The responses to the re-INVITE (200 OK,
		// 487, etc.) arrive at the SipProvider but don't match any registered
		// transaction listener — so mjSIP's onCallAccepted/onCallRefused never
		// fire. Without this fallback, the program would wait for the full
		// timeout instead of exiting when the callee responds.
		sipProvider.addPromiscuousListener((sipProvider, msg) -> {
			if (msg.isResponse()) {
				int code = msg.getStatusLine().getCode();
				String reason = msg.getStatusLine().getReason();
				System.err.println("SIP RECV: " + code + " " + reason);
				System.err.flush();
				// Fallback: if we see a final response (2xx/4xx except 401/407/5xx/6xx)
				// that matches our INVITE's Call-ID and the dialog listener
				// hasn't already handled it, handle it here.
				// 401/407 are auth challenges handled by ExtendedInviteDialog
				// (which re-sends the INVITE with Authorization), so we must NOT
				// treat them as final — doing so would exit before the re-INVITE.
				SipMessage invite = sentInvite;
				// Match only responses to our INVITE, not responses to other
				// requests (e.g. "200 OK" to our CANCEL on timeout, or "200 OK"
				// to a BYE). These share the Call-ID but have a different CSeq
				// method (CANCEL/BYE), and treating them as a successful INVITE
				// acceptance would make call() wrongly return true on timeout.
				if (invite != null && !remoteResponded && code >= 200
						&& code != 401 && code != 407
						&& isInviteResponse(msg)
						&& sameCallId(msg, invite)) {
					System.err.println("CALL: fallback detected final response " + code);
					System.err.flush();
					remoteResponded = true;
					if (code >= 200 && code < 300) {
						success = true;
						CallService.this.reason = "OK";
					} else {
						success = false;
						CallService.this.reason = code + " " + reason;
					}
					latch.countDown();
				}
			} else {
				System.err.println("SIP RECV: " + msg.getRequestLine().getMethod() + " "
						+ msg.getRequestLine().getAddress());
				System.err.flush();
			}
		});

		var listener = new CallListenerAdapter() {
			@Override
			public void onCallAccepted(Call call, SdpMessage sdp, SipMessage resp) {
				System.err.println("CALL: onCallAccepted");
				System.err.flush();
				remoteResponded = true;
				success = true;
				CallService.this.reason = "OK";
				latch.countDown();
			}

			@Override
			public void onCallRefused(Call call, String reason, SipMessage resp) {
				System.err.println("CALL: onCallRefused: " + reason);
				System.err.flush();
				remoteResponded = true;
				success = false;
				CallService.this.reason = reason;
				latch.countDown();
			}

			@Override
			public void onCallRedirected(Call call, String reason, java.util.Vector contactList, SipMessage resp) {
				System.err.println("CALL: onCallRedirected: " + reason);
				System.err.flush();
				remoteResponded = true;
				success = false;
				CallService.this.reason = "Redirected: " + reason;
				latch.countDown();
			}

			@Override
			public void onCallTimeout(Call call) {
				System.err.println("CALL: onCallTimeout");
				System.err.flush();
				success = false;
				CallService.this.reason = "Request Timeout";
				latch.countDown();
			}

			@Override
			public void onCallCancel(Call call, SipMessage cancel) {
				System.err.println("CALL: onCallCancel");
				System.err.flush();
				remoteResponded = true;
				latch.countDown();
			}

			@Override
			public void onCallBye(Call call, SipMessage bye) {
				System.err.println("CALL: onCallBye");
				System.err.flush();
				remoteResponded = true;
				latch.countDown();
			}

			@Override
			public void onCallClosed(Call call, SipMessage resp) {
				System.err.println("CALL: onCallClosed");
				System.err.flush();
				remoteResponded = true;
				latch.countDown();
			}
		};

		SipUser sipUser = new SipUser(new NameAddress(new SipURI(username, serverAddress)), username, serverAddress,
				password);

		ExtendedCall call = new ExtendedCall(sipProvider, sipUser, listener);

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
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
			}
			sipProvider.halt();
			sipProvider.scheduler().scheduler().shutdownNow();
		}
	}

	private void terminateCall() {
		if (terminated) {
			return;
		}
		terminated = true;
		// Only send CANCEL if the remote hasn't responded yet (INVITE still pending).
		// If the callee accepted, refused, cancelled, sent BYE, or the call was closed,
		// remoteResponded is true and sending a CANCEL would be wrong — it would tear
		// down an established call or send a redundant CANCEL for an already-terminated
		// transaction. onCallTimeout does NOT set remoteResponded because the SIP
		// transaction timeout means the callee never responded, so we still need to
		// send CANCEL to stop the callee from ringing.
		if (!remoteResponded) {
			System.err.println("CALL: terminating — sending CANCEL (remote did not respond)");
			System.err.flush();
			sendCancel();
		} else {
			System.err.println("CALL: terminating — remote already responded, no CANCEL needed");
			System.err.flush();
		}
		// Release the latch in case no SIP callback fired (e.g., proxy doesn't
		// route the response back to us). Without this, call() would block
		// until the latch timeout (timeoutSeconds + 5), which could be much
		// longer than the actual timeout.
		latch.countDown();
	}

	// We build the CANCEL manually rather than calling InviteDialog.cancel() or
	// hangup() because: (1) cancel() only sends when the transaction is in
	// PROCEEDING state (after a 1xx response) — if the callee silently ignores
	// the INVITE, the transaction is still in CALLING state and cancel() does
	// nothing; (2) createCancelRequest() adds a duplicate Via header, and
	// TransactionClient would replace the branch again, causing the same
	// branch-mismatch problem. Building from the captured wire INVITE ensures
	// the CANCEL carries the exact Via branch Kamailio expects.
	private void sendCancel() {
		SipMessage invite = sentInvite;
		if (invite == null) {
			return;
		}
		sipProvider.sendMessage(buildCancelRequest(invite));
	}

	private static SipMessage buildCancelRequest(SipMessage inviteReq) {
		SipMessage cancel = new SipMessage();
		cancel.setRequestLine(
				new RequestLine(SipMethods.CANCEL, inviteReq.getRequestLine().getAddress()));
		cancel.addViaHeader(inviteReq.getViaHeader());
		cancel.setToHeader(inviteReq.getToHeader());
		cancel.setFromHeader(inviteReq.getFromHeader());
		cancel.setCallIdHeader(inviteReq.getCallIdHeader());
		cancel.setCSeqHeader(
				new CSeqHeader(inviteReq.getCSeqHeader().getSequenceNumber(), SipMethods.CANCEL));
		cancel.setMaxForwardsHeader(new MaxForwardsHeader(70));
		cancel.setContactHeader(inviteReq.getContactHeader());
		cancel.setBody(null, null);
		return cancel;
	}

	public String getReason() {
		return reason;
	}

	private static boolean isInviteResponse(SipMessage msg) {
		return Optional.of(msg)
				.map(SipMessage::getCSeqHeader)
				.map(CSeqHeader::getMethod)
				.filter(SipMethods.INVITE::equalsIgnoreCase)
				.isPresent();
	}

	private static boolean sameCallId(SipMessage a, SipMessage b) {
		var ca = a.getCallIdHeader();
		var cb = b.getCallIdHeader();
		return ca != null && cb != null && ca.getCallId().equals(cb.getCallId());
	}
}
