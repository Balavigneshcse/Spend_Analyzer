package com.spendanalyzer.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.security.SessionAuthenticator;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Login sessions. The browser holds an opaque random token in an HttpOnly cookie; the database
 * holds only its SHA-256, so a leaked sessions table cannot be used to log in. Because sessions
 * live in the database they survive restarts and can be revoked at once (logout, password reset).
 * <p>
 * A session ends at the first of two limits: {@code app.session.days} after login, or
 * {@code app.session.idle-minutes} (plus {@link #IDLE_GRACE}) after the last request. The page
 * sends a small keep-alive (POST /api/auth/ping) while the user is active, so reading or typing
 * without saving does not count as idle.
 */
@Service
public class SessionService implements SessionAuthenticator {

	/** The __Host- prefix makes the browser insist on Secure, Path=/ and no Domain. */
	static final String SECURE_COOKIE = "__Host-sa_session";

	static final String PLAIN_COOKIE = "sa_session";

	/** last_seen_at is only written when it is older than this, to save a write on most requests. */
	static final Duration LAST_SEEN_STEP = Duration.ofSeconds(30);

	/**
	 * Extra time on top of the idle limit. It covers how far last_seen_at can lag behind the last
	 * request (LAST_SEEN_STEP) and the page's keep-alive, which is sent at most once a minute, so
	 * the page's own countdown always ends before the server's.
	 */
	static final Duration IDLE_GRACE = Duration.ofSeconds(60);

	// 32 bytes in base64url without padding
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

	private final JdbcClient jdbc;

	private final Clock clock;

	private final SecureTokens tokens;

	private final boolean secureCookie;

	private final Duration lifetime;

	private final int idleMinutes;

	/** No request for longer than this ends the session: the idle limit plus the grace. */
	private final Duration idleCutoff;

	public SessionService(JdbcClient jdbc, Clock clock, SecureTokens tokens,
			@Value("${app.cookie.secure:true}") boolean secureCookie, @Value("${app.session.days:7}") int days,
			@Value("${app.session.idle-minutes:5}") int idleMinutes) {
		if (days < 1 || days > 90) {
			throw new IllegalStateException("app.session.days must be between 1 and 90 but is " + days);
		}
		if (idleMinutes < 1 || idleMinutes > 120) {
			throw new IllegalStateException("app.session.idle-minutes must be between 1 and 120 but is " + idleMinutes);
		}
		this.jdbc = jdbc;
		this.clock = clock;
		this.tokens = tokens;
		this.secureCookie = secureCookie;
		this.lifetime = Duration.ofDays(days);
		this.idleMinutes = idleMinutes;
		this.idleCutoff = Duration.ofMinutes(idleMinutes).plus(IDLE_GRACE);
	}

	/** The idle limit the page counts down from (without the grace). */
	public int idleMinutes() {
		return idleMinutes;
	}

	/** A session whose last_seen_at is at or before {@code now} minus this is over. */
	Duration idleCutoff() {
		return idleCutoff;
	}

	private record Found(UUID sessionId, Instant lastSeenAt, AuthUser user) {
	}

	@Override
	public Optional<AuthUser> authenticate(HttpServletRequest request) {
		String token = tokenOf(request);
		if (token == null) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		Optional<Found> found = jdbc.sql("""
				SELECT s.id, s.last_seen_at, u.id AS user_id, u.email, u.name
				FROM sessions s JOIN users u ON u.id = s.user_id
				WHERE s.token_hash = ? AND s.expires_at > ?""")
			.params(SecureTokens.sha256(token), Db.ts(now))
			.query((rs, n) -> new Found(rs.getObject("id", UUID.class), Db.instant(rs, "last_seen_at"),
					new AuthUser(rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("name"))))
			.optional();
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Instant idleSince = now.minus(idleCutoff);
		if (!found.get().lastSeenAt().isAfter(idleSince)) {
			// idle too long: the row is useless now. The guard keeps a row that another request just refreshed.
			jdbc.sql("DELETE FROM sessions WHERE id = ? AND last_seen_at <= ?")
				.params(found.get().sessionId(), Db.ts(idleSince))
				.update();
			return Optional.empty();
		}
		Instant stale = now.minus(LAST_SEEN_STEP);
		if (found.get().lastSeenAt().isBefore(stale)) {
			jdbc.sql("UPDATE sessions SET last_seen_at = ? WHERE id = ? AND last_seen_at < ?")
				.params(Db.ts(now), found.get().sessionId(), Db.ts(stale))
				.update();
		}
		return Optional.of(found.get().user());
	}

	/** Starts a session with a brand-new token (never reuse one across logins). Returns the token for the cookie. */
	public String create(UUID userId) {
		String token = tokens.sessionToken();
		Instant now = clock.instant();
		jdbc.sql("""
				INSERT INTO sessions (id, user_id, token_hash, created_at, last_seen_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(UUID.randomUUID(), userId, SecureTokens.sha256(token), Db.ts(now), Db.ts(now),
					Db.ts(now.plus(lifetime)))
			.update();
		return token;
	}

	/** Ends the session of this token, if there is one. */
	public void delete(String token) {
		if (token != null) {
			jdbc.sql("DELETE FROM sessions WHERE token_hash = ?").param(SecureTokens.sha256(token)).update();
		}
	}

	public void deleteAllOf(UUID userId) {
		jdbc.sql("DELETE FROM sessions WHERE user_id = ?").param(userId).update();
	}

	/** Logs the user out everywhere except in the browser that holds {@code keepToken}. */
	public void deleteAllExcept(UUID userId, String keepToken) {
		if (keepToken == null) {
			deleteAllOf(userId);
			return;
		}
		jdbc.sql("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?")
			.params(userId, SecureTokens.sha256(keepToken))
			.update();
	}

	/** The session token a request carries, or null. Anything that does not look like a token is ignored. */
	public String tokenOf(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		String name = cookieName();
		for (Cookie cookie : cookies) {
			if (name.equals(cookie.getName())) {
				String value = cookie.getValue();
				return value != null && TOKEN.matcher(value).matches() ? value : null;
			}
		}
		return null;
	}

	public String cookieName() {
		return secureCookie ? SECURE_COOKIE : PLAIN_COOKIE;
	}

	/** The Set-Cookie value that logs the browser in. */
	public ResponseCookie cookie(String token) {
		return baseCookie(token).maxAge(lifetime).build();
	}

	/** The Set-Cookie value that removes the cookie from the browser. */
	public ResponseCookie clearingCookie() {
		return baseCookie("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
		// No Domain attribute: the cookie belongs to this exact host only.
		return ResponseCookie.from(cookieName(), value).httpOnly(true).secure(secureCookie).sameSite("Strict").path("/");
	}

}
