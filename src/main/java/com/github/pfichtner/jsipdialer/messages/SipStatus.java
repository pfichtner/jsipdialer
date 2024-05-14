package com.github.pfichtner.jsipdialer.messages;

public enum SipStatus {

	TRYING(100), //
	OK(200), //

	UNAUTHORIZED(401), //
	CALL_DOES_NOT_EXIST(481), //
	BUSY_HERE(486), //
	REQUEST_CANCELLED(487), //

	DECLINE(603);

	final int value;

	SipStatus(int value) {
		this.value = value;
	}

}
