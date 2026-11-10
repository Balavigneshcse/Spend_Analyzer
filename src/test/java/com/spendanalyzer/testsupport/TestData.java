package com.spendanalyzer.testsupport;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.spendanalyzer.security.AuthUser;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Helpers shared by the integration tests of every module. */
public final class TestData {

	private static final AtomicLong COUNTER = new AtomicLong(System.nanoTime() % 1_000_000);

	private TestData() {
	}

	/**
	 * Inserts a verified user straight into the database (no accounts, not onboarded) and returns
	 * it. Every call creates a different user, so tests do not disturb each other.
	 */
	public static AuthUser createUser(JdbcClient jdbc) {
		UUID id = UUID.randomUUID();
		long n = COUNTER.incrementAndGet();
		String email = "user" + n + "-" + id.toString().substring(0, 8) + "@example.com";
		String name = "Test User " + n;
		jdbc.sql("INSERT INTO users (id, email, name, phone, password_hash) VALUES (?, ?, ?, ?, ?)")
			.params(id, email, name, "+919876543210", "{noop}not-a-real-hash")
			.update();
		return new AuthUser(id, email, name);
	}

	/** Makes a MockMvc request run as this user: {@code mvc.perform(get("/api/x").with(TestData.as(user)))}. */
	public static RequestPostProcessor as(AuthUser user) {
		return SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken
			.authenticated(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
	}

}
