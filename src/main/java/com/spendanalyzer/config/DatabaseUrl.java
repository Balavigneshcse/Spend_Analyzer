package com.spendanalyzer.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Turns a provider style connection string
 * ({@code postgresql://user:pass@host:5432/db?sslmode=require}) into the three values Spring needs.
 * Works for Render and Neon. A value that already starts with {@code jdbc:} is passed through.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

	public static final String ENV_NAME = "DATABASE_URL";

	public static Optional<DatabaseUrl> fromEnvironment() {
		String raw = System.getenv(ENV_NAME);
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(parse(raw.trim()));
	}

	public static DatabaseUrl parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException(ENV_NAME + " is empty");
		}
		String value = raw.trim();
		if (value.startsWith("jdbc:postgresql://")) {
			return new DatabaseUrl(value, null, null);
		}
		if (!value.startsWith("postgres://") && !value.startsWith("postgresql://")) {
			throw new IllegalArgumentException(ENV_NAME + " must start with postgres:// or postgresql://");
		}
		URI uri;
		try {
			uri = URI.create(value);
		}
		catch (IllegalArgumentException ex) {
			// never echo the value: it contains the password
			throw new IllegalArgumentException(ENV_NAME + " is not a valid connection string");
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException(ENV_NAME + " has no host");
		}
		int port = uri.getPort() > 0 ? uri.getPort() : 5432;
		String path = uri.getRawPath();
		String database = (path == null || path.length() <= 1) ? "" : decode(path.substring(1));
		if (database.isBlank()) {
			throw new IllegalArgumentException(ENV_NAME + " has no database name");
		}

		String username = null;
		String password = null;
		String userInfo = uri.getRawUserInfo();
		if (userInfo != null && !userInfo.isEmpty()) {
			int colon = userInfo.indexOf(':');
			if (colon >= 0) {
				username = decode(userInfo.substring(0, colon));
				password = decode(userInfo.substring(colon + 1));
			}
			else {
				username = decode(userInfo);
			}
		}

		StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(host).append(':').append(port).append('/')
			.append(database);
		// Keep only sslmode. Other libpq-only options (channel_binding, options, ...) confuse the JDBC driver.
		String sslMode = queryParam(uri.getRawQuery(), "sslmode");
		if (sslMode != null && sslMode.matches("[a-z-]{1,20}")) {
			jdbc.append("?sslmode=").append(sslMode);
		}
		return new DatabaseUrl(jdbc.toString(), username, password);
	}

	public void applyAsSystemProperties() {
		System.setProperty("spring.datasource.url", jdbcUrl);
		if (username != null) {
			System.setProperty("spring.datasource.username", username);
		}
		if (password != null) {
			System.setProperty("spring.datasource.password", password);
		}
	}

	private static String queryParam(String rawQuery, String name) {
		if (rawQuery == null) {
			return null;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(name)) {
				return decode(pair.substring(eq + 1));
			}
		}
		return null;
	}

	private static String decode(String s) {
		// URLDecoder turns '+' into a space, which is wrong for passwords in a URI.
		return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

	@Override
	public String toString() {
		return "DatabaseUrl[jdbcUrl=" + jdbcUrl + ", username=" + username + ", password=***]";
	}

}
