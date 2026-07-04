package com.github.pfichtner.jsipdialer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mjsip.sdp.SdpMessage;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.call.Call;
import org.mjsip.sip.call.CallListenerAdapter;
import org.mjsip.sip.call.ExtendedCall;
import org.mjsip.sip.call.SipUser;
import org.mjsip.sip.provider.SipId;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipProviderListener;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;

@Tag("integration")
class SipClientMainIT {

	@Test
	void callDirect() throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		AtomicInteger status = new AtomicInteger();
		CountDownLatch promiscuousGotResponse = new CountDownLatch(1);

		SchedulerConfig schedConfig = new SchedulerConfig();

		// Receiver
		SipConfig recvConfig = new SipConfig();
		recvConfig.setTransportProtocols(new String[] { "udp" });
		recvConfig.setHostPort(15061);
		recvConfig.setViaAddrIPv4("127.0.0.1");
		recvConfig.setTransactionTimeout(5000);
		recvConfig.setForceRport(true);
		recvConfig.normalize();

		SdpMessage localSdp = SdpMessage.createSdpMessage("receiver", "0.0.0.0");

		SipProvider recvProvider = new SipProvider(recvConfig,
				new ConfiguredScheduler(schedConfig));

		// Add promiscuous listener on receiver to see all messages
		recvProvider.addPromiscuousListener(new SipProviderListener() {
			@Override
			public void onReceivedMessage(SipProvider provider, org.mjsip.sip.message.SipMessage msg) {
				System.err.println("RECV_PROMISC: " + msg.getFirstLine());
				System.err.flush();
			}
		});

		SipUser recvUser = new SipUser(
				new NameAddress(new SipURI("12345", "127.0.0.1")),
				new NameAddress(new SipURI("receiver", "127.0.0.1", 15061)));

		ExtendedCall recvCall = new ExtendedCall(recvProvider, recvUser,
				new CallListenerAdapter() {
			@Override
			public void onCallInvite(Call call, NameAddress callee,
					NameAddress caller, SdpMessage sdp, org.mjsip.sip.message.SipMessage invite) {
				System.err.println("RECV: invite from " + caller + ", request-uri="
						+ invite.getRequestLine().getAddress());
				System.err.flush();
				SdpMessage local = call.getLocalSessionDescriptor();
				System.err.println("RECV: localSdp=" + local);
				System.err.flush();
				call.accept(local);
				System.err.println("RECV: accept called, SDP sent");
				System.err.flush();
			}
		});

		recvCall.setLocalSessionDescriptor(localSdp);
		recvCall.listen();

		Thread.sleep(300);

		// Caller
		SipConfig callConfig = new SipConfig();
		callConfig.setTransportProtocols(new String[] { "udp" });
		callConfig.setHostPort(15063);
		callConfig.setViaAddrIPv4("127.0.0.1");
		callConfig.setOutboundProxy(new SipURI("127.0.0.1", 15061));
		callConfig.setTransactionTimeout(5000);
		callConfig.setUseRport(true);
		callConfig.normalize();

		SipProvider callerProvider = new SipProvider(callConfig,
				new ConfiguredScheduler(schedConfig));

		// Add a promiscuous listener to see all messages arriving at caller
		callerProvider.addPromiscuousListener(new SipProviderListener() {
			@Override
			public void onReceivedMessage(SipProvider provider, SipMessage msg) {
				System.err.println("CALLER_PROMISC: " + msg.getFirstLine());
				if (msg.isRequest()) {
					System.err.println("CALLER_PROMISC: req SipId(client)=" + SipId.createTransactionClientId(msg));
					System.err.println("CALLER_PROMISC: req Via=" + msg.getViaHeader());
				} else {
					SipId respSipId = SipId.createTransactionClientId(msg);
					System.err.println("CALLER_PROMISC: resp SipId=" + respSipId);
					System.err.println("CALLER_PROMISC: resp Via=" + msg.getViaHeader());
				}
				System.err.flush();
				if (!msg.isRequest()) {
					promiscuousGotResponse.countDown();
				}
			}
		});

		SipUser callUser = new SipUser(
				new NameAddress(new SipURI("caller", "127.0.0.1")),
				new NameAddress(new SipURI("caller", "127.0.0.1", 15063)));

		ExtendedCall callerCall = new ExtendedCall(callerProvider, callUser,
				new CallListenerAdapter() {
			@Override
			public void onCallAccepted(Call call, SdpMessage sdp,
					org.mjsip.sip.message.SipMessage resp) {
				System.err.println("CALLER: accepted!");
				System.err.flush();
				status.set(200);
				done.countDown();
			}
			@Override
			public void onCallRefused(Call call, String reason,
					org.mjsip.sip.message.SipMessage resp) {
				System.err.println("CALLER: refused: " + reason);
				System.err.flush();
				status.set(-1);
				done.countDown();
			}
			@Override
			public void onCallTimeout(Call call) {
				System.err.println("CALLER: timeout");
				System.err.flush();
				status.set(-2);
				done.countDown();
			}
		});

		SdpMessage callerSdp = SdpMessage.createSdpMessage("caller", "0.0.0.0");
		callerCall.setLocalSessionDescriptor(callerSdp);
		System.err.println("CALLER: about to call, provider port=" + callerProvider.getPort()
				+ " viaAddr=" + callerProvider.getViaAddress());
		System.err.flush();
		callerCall.call(new NameAddress(new SipURI("12345", "127.0.0.1")));
		System.err.flush();

		System.err.println("CALLER: waiting...");
		System.err.flush();

		boolean promiscGot = promiscuousGotResponse.await(5, TimeUnit.SECONDS);
		System.err.println("CALLER: promiscGot=" + promiscGot);
		System.err.flush();

		boolean ok = done.await(5, TimeUnit.SECONDS);
		System.err.println("CALLER: ok=" + ok + " status=" + status.get());
		System.err.flush();

		callerCall.hangup();
		recvCall.hangup();
		callerProvider.halt();
		recvProvider.halt();

		assertThat(ok).isTrue();
		assertThat(status.get()).isEqualTo(200);
	}
}
