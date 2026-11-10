package com.spendanalyzer.auth.mail;

/**
 * One mail to send. The texts come from {@link MailTemplates} only: nothing a user typed is ever
 * put into a mail.
 * @param kind what it is (REGISTER_CODE, RESET_CODE, ACCOUNT_NOTICE), safe to log
 */
public record EmailMessage(String to, String kind, String subject, String text, String html) {

	/** The recipient for logs: "b***@gmail.com". */
	public String maskedTo() {
		int at = to == null ? -1 : to.indexOf('@');
		return at <= 0 ? "***" : to.charAt(0) + "***" + to.substring(at);
	}

	@Override
	public String toString() {
		// subject and body contain the code: never print them
		return "EmailMessage[kind=" + kind + ", to=" + maskedTo() + "]";
	}

}
