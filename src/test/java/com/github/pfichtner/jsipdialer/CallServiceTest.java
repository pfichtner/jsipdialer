package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.header.CSeqHeader;
import org.mjsip.sip.header.CallIdHeader;
import org.mjsip.sip.header.ContactHeader;
import org.mjsip.sip.header.FromHeader;
import org.mjsip.sip.header.RequestLine;
import org.mjsip.sip.header.SipHeaders;
import org.mjsip.sip.header.StatusLine;
import org.mjsip.sip.header.ToHeader;
import org.mjsip.sip.header.ViaHeader;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.message.SipMessageFactory;
import org.mjsip.sip.message.SipMethods;
import org.mjsip.sip.provider.SipConfig;

class CallServiceTest {

	private static final String BRANCH = "z9hG4bK-branch-1";
	private static final String CALL_ID = "call-1@127.0.0.1";
	private static final String FROM_TAG = "from-tag-1";

	private FakeSipServer server;

	@AfterEach
	void tearDown() throws Exception {
		if (server != null) {
			server.stop();
		}
	}

	// --- unit tests for the response-to-invite matcher ---

	@Test
	void matchesFinalResponseToOurInvite() {
		SipMessage invite = invite(BRANCH, CALL_ID, FROM_TAG);
		SipMessage response = response(200, BRANCH, CALL_ID, FROM_TAG, SipMethods.INVITE);

		assertThat(CallService.isFinalResponseToInvite(response, invite)).isTrue();
	}

	@Test
	void rejectsResponseWithDifferentViaBranchEvenIfCallIdMatches() {
		SipMessage invite = invite(BRANCH, CALL_ID, FROM_TAG);
		SipMessage response = response(200, "z9hG4bK-some-other-branch", CALL_ID, FROM_TAG, SipMethods.INVITE);

		assertThat(CallService.isFinalResponseToInvite(response, invite)).isFalse();
	}

	@Test
	void rejectsResponseWithDifferentCallId() {
		SipMessage invite = invite(BRANCH, CALL_ID, FROM_TAG);
		SipMessage response = response(200, BRANCH, "call-2@127.0.0.1", FROM_TAG, SipMethods.INVITE);

		assertThat(CallService.isFinalResponseToInvite(response, invite)).isFalse();
	}

	@Test
	void rejectsResponseWithDifferentFromTag() {
		SipMessage invite = invite(BRANCH, CALL_ID, FROM_TAG);
		SipMessage response = response(200, BRANCH, CALL_ID, "from-tag-2", SipMethods.INVITE);

		assertThat(CallService.isFinalResponseToInvite(response, invite)).isFalse();
	}

	@Test
	void rejectsResponseToAnotherRequestSharingTheCallId() {
		SipMessage invite = invite(BRANCH, CALL_ID, FROM_TAG);
		SipMessage response = response(200, BRANCH, CALL_ID, FROM_TAG, SipMethods.CANCEL);

		assertThat(CallService.isFinalResponseToInvite(response, invite)).isFalse();
	}

	@Test
	void rejectsInviteWithoutFromTag() {
		SipMessage invite = invite(BRANCH, CALL_ID, null);
		SipMessage response = response(200, BRANCH, CALL_ID, FROM_TAG, SipMethods.INVITE);

		assertThat(CallService.isFinalResponseToInvite(response, invite)).isFalse();
	}

	@Test
	void rejectsResponseWithoutViaBranch() {
		SipMessage invite = invite(BRANCH, CALL_ID, FROM_TAG);
		SipMessage response = response(200, null, CALL_ID, FROM_TAG, SipMethods.INVITE);

		assertThat(CallService.isFinalResponseToInvite(response, invite)).isFalse();
	}

	@Test
	void cancelRequestDoesNotCopyContactHeader() {
		SipMessage invite = invite(BRANCH, CALL_ID, FROM_TAG);
		invite.setContactHeader(new ContactHeader(new NameAddress(new SipURI("alice", "example.com"))));

		SipMessage cancel = CallService.buildCancelRequest(invite);

		assertThat(cancel.getRequestLine().getMethod()).isEqualTo(SipMethods.CANCEL);
		assertThat(cancel.getContactHeader()).isNull();
	}

	// --- end-to-end test: forged response carrying only the right Call-ID is ignored ---

	@Test
	void callSucceedsWhenForgedFinalResponseWithWrongViaBranchIsReceived() throws Exception {
		server = new FakeSipServer();
		server.start();

		CallService callService = new CallService("127.0.0.1", server.getPort(), "user", "pass", "callee", "caller",
				5, "udp", freeUdpPort());

		boolean result = callService.call();

		assertThat(result).isTrue();
		assertThat(server.invitesReceived()).isGreaterThanOrEqualTo(1);
	}

	private static SipMessage invite(String branch, String callId, String fromTag) {
		SipMessage msg = new SipMessage();
		msg.setRequestLine(new RequestLine(SipMethods.INVITE, new SipURI("alice", "example.com")));
		msg.addViaHeader(via(branch));
		msg.setFromHeader(new FromHeader(new SipURI("alice", "example.com"), fromTag));
		msg.setToHeader(new ToHeader(new SipURI("bob", "example.com")));
		msg.setCallIdHeader(new CallIdHeader(callId));
		msg.setCSeqHeader(new CSeqHeader(1, SipMethods.INVITE));
		return msg;
	}

	private static SipMessage response(int code, String branch, String callId, String fromTag, String method) {
		SipMessage msg = new SipMessage();
		msg.setStatusLine(new StatusLine(code, "reason"));
		msg.addViaHeader(via(branch));
		msg.setFromHeader(new FromHeader(new SipURI("alice", "example.com"), fromTag));
		msg.setToHeader(new ToHeader(new SipURI("bob", "example.com")));
		msg.setCallIdHeader(new CallIdHeader(callId));
		msg.setCSeqHeader(new CSeqHeader(1, method));
		return msg;
	}

	private static ViaHeader via(String branch) {
		ViaHeader via = new ViaHeader("UDP", "127.0.0.1", false, 5060);
		via.setBranch(branch);
		return via;
	}

	private static int freeUdpPort() throws Exception {
		try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
			return socket.getLocalPort();
		}
	}

	private static final class FakeSipServer {

		private final DatagramSocket socket;
		private final AtomicInteger invites = new AtomicInteger();
		private final AtomicBoolean running = new AtomicBoolean(true);
		private Thread thread;

		FakeSipServer() throws Exception {
			socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
		}

		int getPort() {
			return socket.getLocalPort();
		}

		int invitesReceived() {
			return invites.get();
		}

		void start() {
			thread = new Thread(this::run, "fake-sip-server");
			thread.setDaemon(true);
			thread.start();
		}

		void stop() throws Exception {
			running.set(false);
			socket.close();
			thread.join(5000);
		}

		private void run() {
			byte[] buf = new byte[65536];
			SipMessageFactory factory = new SipMessageFactory(new SipConfig());
			while (running.get()) {
				try {
					DatagramPacket packet = new DatagramPacket(buf, buf.length);
					socket.receive(packet);
					byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
					SipMessage request = new SipMessage(data, 0, data.length);
					if (request.isInvite() && invites.incrementAndGet() == 1) {
						// Forged final response: attacker knows the Call-ID but not
						// the (random) Via branch of our INVITE.
						SipMessage forged = factory.createResponse(request, 603, "Declined", null);
						forged.removeHeader(SipHeaders.Via);
						forged.addViaHeader(via("z9hG4bK-forged-branch"));
						send(forged, packet.getAddress(), packet.getPort());
						// Give the client a chance to process the forged response,
						// then send the genuine response echoing our INVITE.
						Thread.sleep(400);
						SipMessage genuine = factory.createResponse(request, 200, "OK", null);
						send(genuine, packet.getAddress(), packet.getPort());
					}
				} catch (Exception e) {
					if (running.get()) {
						e.printStackTrace();
					}
				}
			}
		}

		private void send(SipMessage msg, InetAddress address, int port) throws Exception {
			byte[] data = msg.getBytes();
			socket.send(new DatagramPacket(data, data.length, address, port));
		}
	}

}
