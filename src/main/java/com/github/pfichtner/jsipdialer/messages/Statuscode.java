package com.github.pfichtner.jsipdialer.messages;

public class Statuscode {

	private final int value;

	public static Statuscode statuscodeOf(SipStatus status) {
		return new Statuscode(status.value());
	}

	public Statuscode(int value) {
		this.value = value;
	}

	public boolean isTrying() {
		return is(SipStatus.TRYING);
	}

	public boolean isOk() {
		return is(SipStatus.OK);
	}

	public boolean isUnauthorized() {
		return is(SipStatus.UNAUTHORIZED);
	}

	public boolean isBusyHere() {
		return is(SipStatus.BUSY_HERE);
	}

	public boolean isRequestTerminated() {
		return is(SipStatus.REQUEST_CANCELLED);
	}

	public boolean isDecline() {
		return is(SipStatus.DECLINE);
	}

	public boolean is(SipStatus sipStatus) {
		return getValue() == sipStatus.value();
	}

	public int getValue() {
		return value;
	}

	public boolean isOneOf(SipStatus... sipStatus) {
		for (SipStatus status : sipStatus) {
			if (is(status)) {
				return true;
			}
		}
		return false;
	}

}