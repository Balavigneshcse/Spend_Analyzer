package com.spendanalyzer.auth.mail;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ProductionGuard} while the application context is being built, so a wrong
 * configuration stops the start with one clear message. {@link MailDispatcher} depends on this
 * bean: no mail can be sent before the check has passed.
 */
@Component
public class MailStartupCheck {

	public MailStartupCheck(Environment environment) {
		String render = environment.getProperty("RENDER");
		ProductionGuard.check(new ProductionGuard.Settings(environment.acceptsProfiles(Profiles.of("prod")),
				render != null && !render.isBlank(), environment.getProperty("app.mail.provider", ""),
				environment.getProperty("app.mail.brevo.api-key", ""), environment.getProperty("app.mail.from-email", ""),
				environment.getProperty("app.security.otp-pepper", ""),
				environment.getProperty("app.cookie.secure", Boolean.class, Boolean.TRUE)));
	}

}
