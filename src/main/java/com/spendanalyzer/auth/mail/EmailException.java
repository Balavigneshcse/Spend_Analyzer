package com.spendanalyzer.auth.mail;

/** A mail could not be handed to the provider. The message is safe to log. */
public class EmailException extends RuntimeException {

	public EmailException(String message) {
		super(message);
	}

	public EmailException(String message, Throwable cause) {
		super(message, cause);
	}

}
