package com.spendanalyzer.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two limits that depend on the deployment rather than on security reasoning. Everything else
 * (attempts per code, codes per hour, login failures) is a constant in the class that enforces it.
 * @param otpRequestsPerIpPerHour how many "send me a code" requests one client address may make in an hour
 * @param mailsPerDay circuit breaker: mails sent in any 24 hours. Not set (null) means "what the
 * mail provider's free quota allows", see {@link #mailBudget(String)}
 */
@ConfigurationProperties("app.auth.limits")
public record AuthLimits(@DefaultValue("10") int otpRequestsPerIpPerHour, Integer mailsPerDay) {

	/** Below the 300 mails a day of Brevo's free plan. */
	static final int DEFAULT_MAILS_PER_DAY = 200;

	/**
	 * A personal Gmail account may mail 100 recipients in 24 hours through Apps Script, and Google
	 * counts all scripts of the account together. 80 leaves room: the app stops by itself before
	 * Google refuses.
	 */
	static final int GOOGLE_MAILS_PER_DAY = 80;

	public AuthLimits {
		if (otpRequestsPerIpPerHour < 1) {
			throw new IllegalArgumentException("app.auth.limits.otp-requests-per-ip-per-hour must be at least 1");
		}
		if (mailsPerDay != null && mailsPerDay < 1) {
			throw new IllegalArgumentException("app.auth.limits.mails-per-day must be at least 1");
		}
	}

	/**
	 * The circuit breaker's limit: app.auth.limits.mails-per-day when it is set, otherwise what the
	 * free quota of the mail provider (app.mail.provider) allows. This is the only place that knows
	 * the providers' quotas.
	 */
	public int mailBudget(String mailProvider) {
		if (mailsPerDay != null) {
			return mailsPerDay;
		}
		return "google".equals(mailProvider) ? GOOGLE_MAILS_PER_DAY : DEFAULT_MAILS_PER_DAY;
	}

}
