package com.github.pfichtner.jsipdialer;

import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import com.github.pfichtner.jsipdialer.messages.MessageReceived;
import com.github.pfichtner.jsipdialer.messages.Statuscode;

public class Call {

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

	private final String destinationNumber;
	private final String callerName;

	int tagId = random();
	int callId = random();

	MessageReceived received;

	private final int timeout;
	private long startTime;
	private boolean isInProgress;

	private Try inviteTries = new Try(30, MILLISECONDS).maxTries(5);
	private Try inviteWithAuthTries = new Try(30, MILLISECONDS).maxTries(3);
	private Try byeTries = new Try(30, MILLISECONDS).maxTries(5);

	public Call(String destinationNumber, String callerName, int timeout) {
		this.destinationNumber = destinationNumber;
		this.callerName = callerName;
		this.timeout = timeout;
	}

	public String getDestinationNumber() {
		return destinationNumber;
	}

	public String getCallerName() {
		return callerName;
	}

	public Statuscode statuscode() {
		return received == null ? null : received.statuscode();
	}

	public int getTimeout() {
		return timeout;
	}

	private static int random() {
		return new SecureRandom().nextInt(0x3fffffff + 1);
	}

	public boolean isInProgress() {
		return this.isInProgress;
	}

	public void inProgress(boolean inProgress) {
		if (inProgress && !this.isInProgress) {
			this.startTime = currentTimeMillis();
		}
		this.isInProgress = inProgress;
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

	public boolean shouldTryBye() {
		return isTimedout() && byeTries.nowPossibleNext();
	}

	private boolean isTimedout() {
		return currentTimeMillis() - startTime >= timeout * 1000;
	}

	public void increaseBye() {
		byeTries.increase();
	}

	public boolean shouldGiveUp() {
		return byeTries.limitIsReached();
	}

	public void setReceived(MessageReceived received) {
		this.received = received;

	}

}
