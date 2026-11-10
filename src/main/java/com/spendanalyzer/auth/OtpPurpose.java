package com.spendanalyzer.auth;

/** What a code is for. A code issued for one purpose is useless for the other. */
public enum OtpPurpose {

	REGISTER("REGISTER_CODE"),

	PASSWORD_RESET("RESET_CODE");

	private final String mailKind;

	OtpPurpose(String mailKind) {
		this.mailKind = mailKind;
	}

	/** The value written to mail_log.kind when a code of this purpose is mailed. */
	public String mailKind() {
		return mailKind;
	}

}
