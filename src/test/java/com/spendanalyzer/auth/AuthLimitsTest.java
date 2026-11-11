package com.spendanalyzer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The daily mail budget: a configured value always wins; without one it follows the free quota of
 * the mail provider, so the Gmail sender stops well before Google's 100 mails a day.
 */
class AuthLimitsTest {

	/** The limits as Spring Boot binds them from these properties. */
	private static AuthLimits bound(Map<String, String> properties) {
		return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("app.auth.limits",
				AuthLimits.class);
	}

	@Test
	void withoutASettingTheGmailSenderGetsEightyMailsADay() {
		AuthLimits limits = bound(Map.of());

		assertThat(limits.mailsPerDay()).isNull();
		assertThat(limits.otpRequestsPerIpPerHour()).isEqualTo(10);
		assertThat(limits.mailBudget("google")).isEqualTo(80);
		// safely below what a personal Gmail account may send through Apps Script in 24 hours
		assertThat(AuthLimits.GOOGLE_MAILS_PER_DAY).isLessThan(100);
	}

	@Test
	void withoutASettingEveryOtherSenderKeepsTwoHundredMailsADay() {
		AuthLimits limits = bound(Map.of("app.auth.limits.otp-requests-per-ip-per-hour", "3"));

		assertThat(limits.otpRequestsPerIpPerHour()).isEqualTo(3);
		for (String provider : new String[] { "brevo", "console", "capture", "", "Google", null }) {
			assertThat(limits.mailBudget(provider)).as("provider: %s", provider).isEqualTo(200);
		}
	}

	@Test
	void aConfiguredBudgetWinsForEverySender() {
		AuthLimits limits = bound(Map.of("app.auth.limits.mails-per-day", "150"));

		assertThat(limits.mailsPerDay()).isEqualTo(150);
		assertThat(limits.mailBudget("google")).isEqualTo(150);
		assertThat(limits.mailBudget("brevo")).isEqualTo(150);
		assertThat(bound(Map.of("app.auth.limits.mails-per-day", "5")).mailBudget("google")).isEqualTo(5);
	}

	@Test
	void theBudgetCanBeSetWithTheEnvironmentVariableOfTheReadme() {
		SystemEnvironmentPropertySource environment = new SystemEnvironmentPropertySource(
				StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				Map.<String, Object>of("APP_AUTH_LIMITS_MAILSPERDAY", "60"));

		AuthLimits limits = new Binder(ConfigurationPropertySources.from(environment)).bindOrCreate("app.auth.limits",
				AuthLimits.class);

		assertThat(limits.mailBudget("google")).isEqualTo(60);
		assertThat(limits.mailBudget("brevo")).isEqualTo(60);
	}

	@Test
	void anEmptySettingCountsAsNotSet() {
		// an environment variable that exists but is empty must not switch the breaker off
		assertThat(bound(Map.of("app.auth.limits.mails-per-day", "")).mailBudget("google")).isEqualTo(80);
	}

	@Test
	void aBudgetBelowOneIsRefused() {
		assertThatThrownBy(() -> bound(Map.of("app.auth.limits.mails-per-day", "0")))
			.hasRootCauseInstanceOf(IllegalArgumentException.class)
			.rootCause()
			.hasMessage("app.auth.limits.mails-per-day must be at least 1");
		assertThatThrownBy(() -> new AuthLimits(10, -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuthLimits(0, null)).isInstanceOf(IllegalArgumentException.class);
	}

}
