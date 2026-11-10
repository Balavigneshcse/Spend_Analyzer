package com.spendanalyzer.auth.mail;

/**
 * The only mails this application sends. Fixed wording: the code, what it is for, how long it is
 * valid and a warning. No name or other text typed by a user (anybody can type anybody's address
 * into the forms) and no links (so nothing is ever built from the request's Host header).
 */
public final class MailTemplates {

	public static final String KIND_REGISTER_CODE = "REGISTER_CODE";

	public static final String KIND_RESET_CODE = "RESET_CODE";

	public static final String KIND_ACCOUNT_NOTICE = "ACCOUNT_NOTICE";

	private static final String WARNING = "Never share this code. If this was not you, ignore this email.";

	private MailTemplates() {
	}

	public static EmailMessage registerCode(String to, String code, long validMinutes) {
		return codeMail(to, KIND_REGISTER_CODE, code, "to finish creating your Spend Analyzer account", validMinutes);
	}

	public static EmailMessage resetCode(String to, String code, long validMinutes) {
		return codeMail(to, KIND_RESET_CODE, code, "to reset your Spend Analyzer password", validMinutes);
	}

	/** Sent instead of a code when somebody registers with an address that already has an account. */
	public static EmailMessage accountExists(String to) {
		String subject = "You already have a Spend Analyzer account";
		String first = "Someone tried to create a Spend Analyzer account with this email address, "
				+ "but it already has one.";
		String second = "If it was you, log in as usual. If you forgot your password, "
				+ "choose \"Forgot password\" on the login page.";
		String third = "If this was not you, ignore this email. Your account has not changed.";
		String text = first + "\n\n" + second + "\n\n" + third + "\n";
		String html = page("<p>" + first + "</p><p>" + escape(second) + "</p><p>" + third + "</p>");
		return new EmailMessage(to, KIND_ACCOUNT_NOTICE, subject, text, html);
	}

	private static EmailMessage codeMail(String to, String kind, String code, String purpose, long validMinutes) {
		// The code is in the subject too: in a spam folder often only the subject is visible.
		String subject = code + " is your Spend Analyzer code";
		String use = "Use this code " + purpose + ".";
		String valid = "It is valid for " + validMinutes + " minutes.";
		String text = "Your Spend Analyzer code is " + code + "\n\n" + use + "\n" + valid + "\n\n" + WARNING + "\n";
		String html = page("<p>Your Spend Analyzer code is</p>"
				+ "<p style=\"font-size:28px;font-weight:bold;letter-spacing:4px;margin:16px 0\">" + code + "</p>"
				+ "<p>" + use + "<br>" + valid + "</p><p>" + WARNING + "</p>");
		return new EmailMessage(to, kind, subject, text, html);
	}

	private static String page(String body) {
		return "<!DOCTYPE html><html><body style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;"
				+ "color:#222\">" + body + "</body></html>";
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

}
