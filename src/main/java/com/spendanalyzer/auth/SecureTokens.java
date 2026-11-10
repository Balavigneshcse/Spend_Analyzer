package com.spendanalyzer.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/** The one source of randomness for everything secret: session tokens, codes, salts. */
@Component
public class SecureTokens {

	private static final int SESSION_TOKEN_BYTES = 32;

	// new SecureRandom() never blocks; getInstanceStrong() can hang on a small container.
	private final SecureRandom random = new SecureRandom();

	public SecureRandom random() {
		return random;
	}

	/** 32 random bytes as base64url: 43 characters. Only the browser cookie ever holds it. */
	public String sessionToken() {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(SESSION_TOKEN_BYTES));
	}

	/** A six digit code, every value from 000000 to 999999 equally likely. */
	public String otpCode() {
		return String.format("%06d", random.nextInt(1_000_000));
	}

	public byte[] bytes(int count) {
		byte[] bytes = new byte[count];
		random.nextBytes(bytes);
		return bytes;
	}

	public static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is missing from this JDK", ex);
		}
	}

}
