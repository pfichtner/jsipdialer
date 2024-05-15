package com.github.pfichtner.jsipdialer;

import static java.lang.System.currentTimeMillis;

import java.security.SecureRandom;

import com.github.pfichtner.jsipdialer.messages.MessageReceived;

public class Call {

	final String destinationNumber;
	final String callerName;

	int tagId = random();
	int callId = random();

	MessageReceived received;

	private final int timeout;
	private long startTime;
	private boolean isInProgress;

	private int inviteTries;
	private int inviteWithAuthTries;
	private long lastInviteTry;

	public Call(String destinationNumber, String callerName, int timeout) {
		this.destinationNumber = destinationNumber;
		this.callerName = callerName;
		this.timeout = timeout;
	}

	public boolean isTimedout() {
		return currentTimeMillis() - startTime >= timeout * 1000;
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
		inviteTries++;
		lastInviteTry = System.currentTimeMillis();
	}

	public void increaseInvitesWithAuth() {
		inviteWithAuthTries++;
	}

	public boolean shouldTryInvite() {
		return received == null && inviteWithAuthTries == 0 && inviteTries < 5
				&& currentTimeMillis() + 30 > lastInviteTry;
	}

	public boolean shouldTryInviteWithAuth() {
		return inviteWithAuthTries < 3;
	}

}