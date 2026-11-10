package com.spendanalyzer.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The password rule, used by registration, password reset and password change alike:
 * 10 to 64 characters (and at most 72 bytes, the BCrypt limit), a lower-case letter, an upper-case
 * letter, a digit and a symbol, not containing the email name or the phone number, and not one of
 * the well-known passwords in {@code auth/common-passwords.txt}.
 * <p>
 * The page shows the same rule while typing, but only this class decides.
 */
@Component
public class PasswordPolicy {

	public static final int MIN_LENGTH = 10;

	public static final int MAX_LENGTH = 64;

	/** Shorter email names ("bk@...") would forbid half the alphabet. */
	private static final int MIN_EMAIL_NAME_LENGTH = 3;

	private static final String COMMON_PASSWORDS = "/auth/common-passwords.txt";

	private final Set<String> commonPasswords;

	public PasswordPolicy() {
		this.commonPasswords = loadCommonPasswords();
	}

	/**
	 * Checks everything that can be checked without knowing the user.
	 * @return the message for the user, or empty when the password is acceptable
	 */
	public Optional<String> problem(String password) {
		if (password == null || password.isEmpty()) {
			return Optional.of("Enter a password.");
		}
		if (password.codePoints().anyMatch(Character::isISOControl)) {
			return Optional.of("The password contains a character that cannot be used.");
		}
		int length = password.codePointCount(0, password.length());
		if (length < MIN_LENGTH) {
			return Optional.of("Use at least " + MIN_LENGTH + " characters.");
		}
		if (length > MAX_LENGTH) {
			return Optional.of("Use at most " + MAX_LENGTH + " characters.");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > PasswordHasher.MAX_PASSWORD_BYTES) {
			return Optional.of("That password is too long for the letters it uses. Make it shorter.");
		}
		// Before the character classes: "add an upper-case letter" would be poor advice for "password@123".
		if (commonPasswords.contains(password.toLowerCase(Locale.ROOT))) {
			return Optional.of("That password is too well known. Choose another one.");
		}
		List<String> missing = missingClasses(password);
		if (!missing.isEmpty()) {
			return Optional.of("Add " + String.join(", ", missing) + ".");
		}
		return Optional.empty();
	}

	/** The full rule: {@link #problem(String)} plus "does not contain your email name or phone number". */
	public Optional<String> problem(String password, String email, String phone) {
		Optional<String> general = problem(password);
		if (general.isPresent()) {
			return general;
		}
		return personalProblem(password, email, phone);
	}

	/**
	 * Only the personal part of the rule. Either value may be null when it is not known.
	 * @param email a normalised address
	 * @param phone a stored phone number (+91...)
	 */
	public Optional<String> personalProblem(String password, String email, String phone) {
		if (password == null) {
			return Optional.empty();
		}
		String lower = password.toLowerCase(Locale.ROOT);
		if (email != null) {
			String name = EmailAddress.localPart(email);
			if (name.length() >= MIN_EMAIL_NAME_LENGTH && lower.contains(name)) {
				return Optional.of("Do not put your email name in the password.");
			}
		}
		if (phone != null && password.contains(IndianPhone.national(phone))) {
			return Optional.of("Do not put your phone number in the password.");
		}
		return Optional.empty();
	}

	private static List<String> missingClasses(String password) {
		boolean lower = false;
		boolean upper = false;
		boolean digit = false;
		boolean symbol = false;
		for (int i = 0; i < password.length();) {
			int cp = password.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isDigit(cp)) {
				digit = true;
			}
			else if (Character.isUpperCase(cp)) {
				upper = true;
			}
			else if (Character.isLowerCase(cp)) {
				lower = true;
			}
			else if (!Character.isLetter(cp)) {
				symbol = true;
			}
		}
		List<String> missing = new ArrayList<>();
		if (!lower) {
			missing.add("a lower-case letter");
		}
		if (!upper) {
			missing.add("an upper-case letter");
		}
		if (!digit) {
			missing.add("a digit");
		}
		if (!symbol) {
			missing.add("a symbol such as @ or #");
		}
		return missing;
	}

	private static Set<String> loadCommonPasswords() {
		Set<String> passwords = new HashSet<>();
		try (InputStream in = PasswordPolicy.class.getResourceAsStream(COMMON_PASSWORDS)) {
			if (in == null) {
				throw new IllegalStateException("Missing resource " + COMMON_PASSWORDS);
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				String entry = line.strip();
				// "#" alone or "# text" is a comment; a real password may still start with '#'
				if (!entry.isEmpty() && !entry.equals("#") && !entry.startsWith("# ")) {
					passwords.add(entry.toLowerCase(Locale.ROOT));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + COMMON_PASSWORDS, ex);
		}
		return Set.copyOf(passwords);
	}

}
