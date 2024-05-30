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

	public boolean is1xx() {
		return hundreds() == 1;
	}

	public boolean is2xx() {
		return hundreds() == 2;
	}

	private int hundreds() {
		return this.value / 100;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + value;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Statuscode other = (Statuscode) obj;
		if (value != other.value)
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "Statuscode [value=" + value + "]";
	}

}