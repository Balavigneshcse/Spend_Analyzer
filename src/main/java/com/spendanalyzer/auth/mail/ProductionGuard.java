package com.spendanalyzer.auth.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * The start-up rules that keep a half-configured deployment from going live. Pure logic over the
 * configuration values, so it can be tested without starting anything.
 * <p>
 * In production the codes must really be mailed (never logged), the OTP pepper must be a real
 * secret and the session cookie must be Secure. Two senders really mail: "google" (the owner's
 * Gmail through an Apps Script web app) and "brevo". Each needs its own settings and only those:
 * a deployment that uses one of them does not have to set the variables of the other.
 */
public final class ProductionGuard {

	public static final int MIN_PEPPER_LENGTH = 32;

	/**
	 * @param prodProfile the "prod" Spring profile is active
	 * @param onRender the RENDER environment variable is set (the hosting platform sets it)
	 */
	public record Settings(boolean prodProfile, boolean onRender, String mailProvider, String brevoApiKey,
			String fromEmail, String googleScriptUrl, String googleSecret, String otpPepper, boolean cookieSecure) {

		public boolean production() {
			return prodProfile || onRender;
		}

		@Override
		public String toString() {
			// holds secrets: print nothing but the non-secret switches
			return "Settings[prodProfile=" + prodProfile + ", onRender=" + onRender + ", mailProvider=" + mailProvider
					+ "]";
		}

	}

	private ProductionGuard() {
	}

	/** Everything that is wrong with the settings; empty when the application may start. */
	public static List<String> problems(Settings settings) {
		List<String> problems = new ArrayList<>();
		if (isBlank(settings.otpPepper())) {
			problems.add("app.security.otp-pepper (OTP_PEPPER) is not set");
		}
		if (!settings.production()) {
			return problems;
		}
		if ("google".equals(settings.mailProvider())) {
			String url = GoogleScriptEmailSender.urlProblem(settings.googleScriptUrl());
			if (url != null) {
				problems.add("app.mail.google.script-url (GOOGLE_SCRIPT_URL) " + url);
			}
			String secret = GoogleScriptEmailSender.secretProblem(settings.googleSecret());
			if (secret != null) {
				problems.add("app.mail.google.secret (GOOGLE_SCRIPT_SECRET) " + secret);
			}
		}
		else if ("brevo".equals(settings.mailProvider())) {
			if (isBlank(settings.brevoApiKey())) {
				problems.add("app.mail.brevo.api-key (BREVO_API_KEY) is not set");
			}
			if (isBlank(settings.fromEmail())) {
				problems.add("app.mail.from-email (MAIL_FROM_EMAIL) is not set");
			}
		}
		else {
			// the console sender would write every code into the hosting provider's log viewer
			problems.add("app.mail.provider (MAIL_PROVIDER) must be \"google\" or \"brevo\" in production but is \""
					+ settings.mailProvider() + "\" (codes must be mailed, never written to the log)");
		}
		if (!isBlank(settings.otpPepper()) && settings.otpPepper().length() < MIN_PEPPER_LENGTH) {
			problems.add("app.security.otp-pepper (OTP_PEPPER) must be at least " + MIN_PEPPER_LENGTH
					+ " characters long");
		}
		if (!settings.cookieSecure()) {
			problems.add("app.cookie.secure must be true in production");
		}
		return problems;
	}

	/** @throws IllegalStateException naming every problem (never a secret value) */
	public static void check(Settings settings) {
		List<String> problems = problems(settings);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Spend Analyzer cannot start: " + String.join("; ", problems) + ".");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
