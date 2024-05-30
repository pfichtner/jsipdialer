package com.github.pfichtner.jsipdialer;

import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.github.pfichtner.jsipdialer.messages.CSeq;
import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.Statuscode;

public class Call {

	enum State {
		INIT, INVITE_TRYING, CALL_ACTIVE, TERMINATED
	}

	private static class Try {

		private final long milliseconds;

		private int maxTries = 1;
		private long lastTry;
		public int counter;

		public Try(int amount, TimeUnit timeUnit) {
			this.milliseconds = timeUnit.toMillis(amount);
		}

		private Try maxTries(int maxTries) {
			this.maxTries = maxTries;
			return this;
		}

		public void increase() {
			counter++;
			lastTry = currentTimeMillis();
		}

		public boolean nowPossibleNext() {
			return !limitIsReached() && currentTimeMillis() > lastTry + milliseconds;
		}

		public boolean limitIsReached() {
			return counter >= maxTries;
		}

	}

	private final int callId = random();
	private final int tagId = random();
	private final String destinationNumber;
	private final String callerName;
	private final int timeout;

	private CSeq cSeq = CSeq.of(1);
	private MessageReceived received;
	private State state = State.INIT;
	private long startTime;

	private Try inviteTries = new Try(30, MILLISECONDS).maxTries(5);
	private Try inviteWithAuthTries = new Try(30, MILLISECONDS).maxTries(3);
	private Try byeTries = new Try(30, MILLISECONDS).maxTries(5);

	public Call(String destinationNumber, String callerName, int timeout) {
		this.destinationNumber = destinationNumber;
		this.callerName = callerName;
		this.timeout = timeout;
	}

	public int callId() {
		return callId;
	}

	public int tagId() {
		return tagId;
	}

	public String destinationNumber() {
		return destinationNumber;
	}

	public String callerName() {
		return callerName;
	}

	public int timeout() {
		return timeout;
	}

	public void increaeSeq() {
		this.cSeq = cSeq.next();
	}

	public CSeq cSeq() {
		return cSeq;
	}

	public void received(MessageReceived received) {
		this.received = received;
	}

	public MessageReceived received() {
		return received;
	}

	public State state() {
		return state;
	}

	public void state(State state) {
		if (this.state == State.INIT && state != State.INIT) {
			this.startTime = currentTimeMillis();
		}
		this.state = state;
	}

	public boolean stateIs(State state) {
		return Objects.equals(this.state, state);
	}

	public Statuscode statuscode() {
		return received == null ? null : received.statuscode();
	}

	private static int random() {
		return new SecureRandom().nextInt(0x3fffffff + 1);
	}

	public void increaseInvites() {
		System.currentTimeMillis();
		inviteTries.increase();
	}

	public void increaseInvitesWithAuth() {
		inviteWithAuthTries.increase();
	}

	public boolean shouldTryInvite() {
		return received == null && inviteWithAuthTries.counter == 0 && inviteTries.nowPossibleNext();
	}

	public boolean shouldTryInviteWithAuth() {
		return inviteWithAuthTries.nowPossibleNext();
	}

	public boolean shouldTryTerminate() {
		return isTimedout() && byeTries.nowPossibleNext();
	}

	private boolean isTimedout() {
		return currentTimeMillis() - startTime >= timeout * 1000;
	}

	public void increaseTerminate() {
		byeTries.increase();
	}

	public boolean shouldGiveUp() {
		return byeTries.limitIsReached();
	}

}
