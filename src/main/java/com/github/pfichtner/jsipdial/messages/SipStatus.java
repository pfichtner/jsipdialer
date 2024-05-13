package com.github.pfichtner.jsipdial.messages;

public enum SipStatus {

	TRYING(100), //
	OK(200), //

	UNAUTHORIZED(401), //
	BUSY_HERE(486), //
	REQUEST_CANCELLED(487), //

	DECLINE(603);

	final int value;

	SipStatus(int value) {
		this.value = value;
	}

}
