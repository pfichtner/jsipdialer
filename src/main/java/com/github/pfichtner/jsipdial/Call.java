package com.github.pfichtner.jsipdial;

import static java.lang.System.currentTimeMillis;

import java.security.SecureRandom;

import com.github.pfichtner.jsipdial.messages.MessageReceived;

public class Call {

	final String destinationNumber;
	final String callerName;

	int tagId = random();
	int branchId = random();
	int callId = random();

	int authTries;
	MessageReceived received;

	private final int timeout;
	private long startTime;
	private boolean isInProgress;

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

	public void isInProgress(boolean isInProgress) {
		if (isInProgress && !this.isInProgress) {
			this.startTime = currentTimeMillis();
		}
		this.isInProgress = isInProgress;
	}

}
