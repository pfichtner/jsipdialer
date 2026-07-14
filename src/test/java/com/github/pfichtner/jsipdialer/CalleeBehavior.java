package com.github.pfichtner.jsipdialer;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.mjsip.sip.call.Call;

public enum CalleeBehavior implements RegisteredCallee.CalleeAction {
	ACCEPT {
		@Override
		public void onInvite(Call call, RegisteredCallee.ResponseSender respond, ScheduledExecutorService executor) {
			call.accept(call.getLocalSessionDescriptor());
		}
	},
	ACCEPT_THEN_BYE {
		@Override
		public void onInvite(Call call, RegisteredCallee.ResponseSender respond, ScheduledExecutorService executor) {
			call.accept(call.getLocalSessionDescriptor());
			executor.submit(() -> {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				call.hangup();
			});
		}
	},
	REFUSE {
		@Override
		public void onInvite(Call call, RegisteredCallee.ResponseSender respond, ScheduledExecutorService executor) {
			call.refuse();
		}
	},
	IGNORE {
		@Override
		public void onInvite(Call call, RegisteredCallee.ResponseSender respond, ScheduledExecutorService executor) {
		}
	},
	PROVISIONAL_183 {
		@Override
		public void onInvite(Call call, RegisteredCallee.ResponseSender respond, ScheduledExecutorService executor) {
			respond.send(183, "Session Progress");
		}
	},
	PROVISIONAL_183_THEN_REFUSE {
		@Override
		public void onInvite(Call call, RegisteredCallee.ResponseSender respond, ScheduledExecutorService executor) {
			respond.send(183, "Session Progress");
			call.refuse();
		}
	},
	RINGING_THEN_ACCEPT {
		@Override
		public void onInvite(Call call, RegisteredCallee.ResponseSender respond, ScheduledExecutorService executor) {
			respond.send(180, "Ringing");
			executor.schedule(() -> call.accept(call.getLocalSessionDescriptor()), 2, TimeUnit.SECONDS);
		}
	},
	RINGING_THEN_DECLINE {
		@Override
		public void onInvite(Call call, RegisteredCallee.ResponseSender respond, ScheduledExecutorService executor) {
			respond.send(180, "Ringing");
			executor.schedule(() -> call.refuse(), 2, TimeUnit.SECONDS);
		}
	}
}
