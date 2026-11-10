package com.spendanalyzer.auth.mail;

/**
 * Delivers one mail. Exactly one implementation is active, chosen by {@code app.mail.provider}:
 * "brevo" in production, "console" for local development, "capture" in tests.
 * Called on the mail thread of {@link MailDispatcher}, never on a request thread.
 */
public interface EmailSender {

	/**
	 * @throws EmailException when the mail was not accepted; the message of the exception must not
	 * contain the code or the full recipient because it is logged
	 */
	void send(EmailMessage message);

}
