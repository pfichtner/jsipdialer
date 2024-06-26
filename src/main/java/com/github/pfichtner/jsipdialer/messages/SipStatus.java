package com.github.pfichtner.jsipdialer.messages;

public enum SipStatus {

	TRYING(100), //
	OK(200), //

	UNAUTHORIZED(401), //
	CALL_DOES_NOT_EXIST(481), //
	BUSY_HERE(486), //
	REQUEST_CANCELLED(487), //
	REQUEST_PENDING(491), //

	DECLINE(603);

	private final int value;

	SipStatus(int value) {
		this.value = value;
	}

	public int value() {
		return value;
	}

}
