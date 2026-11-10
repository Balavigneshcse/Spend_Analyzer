package com.spendanalyzer.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What is stored instead of a code: HMAC-SHA256 with a server-side secret (the "pepper").
 * A plain hash of six digits could be reversed in a second from a leaked table; with the pepper,
 * which is not in the database, it cannot. The challenge id, purpose and email are part of the
 * message, so a stored value is useless for any other challenge, purpose or address.
 */
@Component
class OtpHmac {

	private static final String ALGORITHM = "HmacSHA256";

	static final int LENGTH = 32;

	private final SecretKeySpec key;

	OtpHmac(@Value("${app.security.otp-pepper:}") String pepper) {
		if (pepper == null || pepper.isBlank()) {
			throw new IllegalStateException("app.security.otp-pepper (OTP_PEPPER) is not set");
		}
		this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	byte[] of(UUID challengeId, OtpPurpose purpose, String email, String code) {
		String message = challengeId + "|" + purpose.name() + "|" + email + "|" + code;
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 is not available", ex);
		}
	}

}
