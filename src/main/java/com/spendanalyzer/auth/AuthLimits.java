package com.spendanalyzer.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two limits that depend on the deployment rather than on security reasoning. Everything else
 * (attempts per code, codes per hour, login failures) is a constant in the class that enforces it.
 * @param otpRequestsPerIpPerHour how many "send me a code" requests one client address may make in an hour
 * @param mailsPerDay circuit breaker: mails sent in any 24 hours, kept below the mail provider's free quota
 */
@ConfigurationProperties("app.auth.limits")
public record AuthLimits(@DefaultValue("10") int otpRequestsPerIpPerHour, @DefaultValue("200") int mailsPerDay) {

	public AuthLimits {
		if (otpRequestsPerIpPerHour < 1) {
			throw new IllegalArgumentException("app.auth.limits.otp-requests-per-ip-per-hour must be at least 1");
		}
		if (mailsPerDay < 1) {
			throw new IllegalArgumentException("app.auth.limits.mails-per-day must be at least 1");
		}
	}

}
