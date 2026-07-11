import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.call.*;

public class TestSip {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting test...");
        
        // Create caller provider
        org.mjsip.sip.provider.SipConfig callerConfig = new org.mjsip.sip.provider.SipConfig();
        callerConfig.setTransportProtocols(new String[]{"udp"});
        callerConfig.setHostPort(15062);
        callerConfig.setOutboundProxy(new SipURI("127.0.0.1", 15060));
        callerConfig.normalize();
        
        org.mjsip.time.SchedulerConfig schedCfg = new org.mjsip.time.SchedulerConfig();
        org.mjsip.time.ConfiguredScheduler sched = new org.mjsip.time.ConfiguredScheduler(schedCfg);
        org.mjsip.sip.provider.SipProvider callerProvider = new org.mjsip.sip.provider.SipProvider(callerConfig, sched);
        System.out.println("Caller provider port: " + callerProvider.getPort());
        
        CountDownLatch latch = new CountDownLatch(1);
        
        var listener = new CallListenerAdapter() {
            @Override
            public void onCallAccepted(Call call, org.mjsip.sdp.SdpMessage sdp, org.mjsip.sip.message.SipMessage resp) {
                System.out.println("onCallAccepted");
                latch.countDown();
            }
            @Override
            public void onCallRefused(Call call, String reason, org.mjsip.sip.message.SipMessage resp) {
                System.out.println("onCallRefused: " + reason);
                latch.countDown();
            }
            @Override
            public void onCallTimeout(Call call) {
                System.out.println("onCallTimeout");
                latch.countDown();
            }
            @Override
            public void onCallCancel(Call call, org.mjsip.sip.message.SipMessage cancel) {
                System.out.println("onCallCancel");
                latch.countDown();
            }
            @Override
            public void onCallBye(Call call, org.mjsip.sip.message.SipMessage bye) {
                System.out.println("onCallBye");
                latch.countDown();
            }
            @Override
            public void onCallClosed(Call call, org.mjsip.sip.message.SipMessage resp) {
                System.out.println("onCallClosed");
                latch.countDown();
            }
            @Override
            public void onCallRedirected(Call call, String reason, java.util.Vector contactList, org.mjsip.sip.message.SipMessage resp) {
                System.out.println("onCallRedirected: " + reason);
                latch.countDown();
            }
        };
        
        SipUser sipUser = new SipUser(
            new NameAddress(new SipURI("caller", "127.0.0.1")),
            "caller", "127.0.0.1", "pass");
        
        ExtendedCall call = new ExtendedCall(callerProvider, sipUser, listener);
        
        javax.sdp.SdpFactory sdpFactory = javax.sdp.SdpFactory.getInstance();
        
        NameAddress callee = new NameAddress(new SipURI("12345", "127.0.0.1"));
        NameAddress caller = new NameAddress(new SipURI("caller", "127.0.0.1"));
        
        org.mjsip.sdp.SdpMessage sdpOffer = org.mjsip.sdp.SdpMessage.createSdpMessage("caller", "0.0.0.0");
        
        System.out.println("Calling " + callee + " from " + caller);
        call.call(callee, caller, sdpOffer);
        System.out.println("Call initiated, waiting...");
        
        boolean triggered = latch.await(30, TimeUnit.SECONDS);
        System.out.println("Latch triggered: " + triggered);
        
        callerProvider.halt();
    }
}
