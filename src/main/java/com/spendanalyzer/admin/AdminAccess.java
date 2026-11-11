package com.spendanalyzer.admin;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who is an administrator: a normal account whose login email is listed in {@code app.admin.emails}
 * (the ADMIN_EMAILS environment variable, comma-separated). Nothing in the app can change the list;
 * only the server's settings can. Empty or missing means nobody is an administrator.
 */
@Component
public class AdminAccess {

	private static final Logger log = LoggerFactory.getLogger(AdminAccess.class);

	/** A short list is all the owner needs; a long one is more likely a mistake. */
	static final int MAX_EMAILS = 20;

	private final Set<String> emails;

	public AdminAccess(@Value("${app.admin.emails:}") String configured) {
		this.emails = parse(configured);
		// how many, never which: the addresses are personal
		log.info("Administrators configured: {}", emails.size());
	}

	/** Split on commas, trimmed, blanks dropped, lower-cased like login emails, at most {@link #MAX_EMAILS}. */
	static Set<String> parse(String configured) {
		Set<String> found = new LinkedHashSet<>();
		if (configured == null) {
			return Set.of();
		}
		for (String part : configured.split(",")) {
			String email = part.strip().toLowerCase(Locale.ROOT);
			if (!email.isEmpty() && found.size() < MAX_EMAILS) {
				found.add(email);
			}
		}
		return Set.copyOf(found);
	}

	/** True only for a listed email. Login emails are stored lower-case; the same lower-casing is applied here. */
	public boolean isAdmin(String email) {
		return email != null && !emails.isEmpty() && emails.contains(email.strip().toLowerCase(Locale.ROOT));
	}

	/** How many emails are listed (for tests and the start-up log). */
	int count() {
		return emails.size();
	}

}
