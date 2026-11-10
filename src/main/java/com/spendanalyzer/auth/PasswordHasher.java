package com.spendanalyzer.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes and checks passwords. BCrypt behind a "{bcrypt}" prefix, so the algorithm can be changed
 * later without a migration.
 * <p>
 * BCrypt is slow on purpose and the production host has a tenth of a CPU, so at most two hashes run
 * at a time. A flood of logins then waits here (or is told to come back) and cannot eat the CPU or
 * every request thread.
 */
@Component
public class PasswordHasher {

	/** BCrypt reads only the first 72 bytes; longer input is refused instead of silently cut. */
	public static final int MAX_PASSWORD_BYTES = 72;

	private static final int MIN_COST = 10;

	private static final int MAX_COST = 15;

	private static final Duration MAX_WAIT = Duration.ofSeconds(5);

	private final PasswordEncoder encoder;

	private final Semaphore permits = new Semaphore(2, true);

	/** Compared against when there is no such user, so that answer takes as long as a real check. */
	private final String dummyHash;

	public PasswordHasher(@Value("${app.security.bcrypt-cost:10}") int cost, SecureTokens tokens) {
		if (cost < MIN_COST || cost > MAX_COST) {
			throw new IllegalStateException(
					"app.security.bcrypt-cost must be between " + MIN_COST + " and " + MAX_COST + " but is " + cost);
		}
		this.encoder = new DelegatingPasswordEncoder("bcrypt",
				Map.of("bcrypt", new BCryptPasswordEncoder(cost, tokens.random())));
		this.dummyHash = this.encoder.encode(tokens.sessionToken());
	}

	/** @param rawPassword a password that already passed {@link PasswordPolicy} (so at most 72 bytes) */
	public String hash(String rawPassword) {
		return withPermit(() -> encoder.encode(rawPassword));
	}

	/**
	 * Checks a password. Pass {@code null} as the stored hash when the user does not exist: the same
	 * amount of work is done and the answer is false.
	 */
	public boolean matches(String rawPassword, String storedHash) {
		boolean usable = rawPassword != null && !rawPassword.isEmpty()
				&& rawPassword.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
		String candidate = usable ? rawPassword : "x";
		String hash = storedHash == null ? dummyHash : storedHash;
		return withPermit(() -> {
			try {
				return encoder.matches(candidate, hash) && usable && storedHash != null;
			}
			catch (IllegalArgumentException ex) {
				// a stored value this encoder does not understand can never match
				return false;
			}
		});
	}

	/** True when the stored hash was made with weaker settings than today's and should be redone. */
	public boolean needsRehash(String storedHash) {
		try {
			return encoder.upgradeEncoding(storedHash);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private <T> T withPermit(Supplier<T> work) {
		boolean acquired;
		try {
			acquired = permits.tryAcquire(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			acquired = false;
		}
		if (!acquired) {
			throw AuthErrors.tooMany(MAX_WAIT);
		}
		try {
			return work.get();
		}
		finally {
			permits.release();
		}
	}

}
