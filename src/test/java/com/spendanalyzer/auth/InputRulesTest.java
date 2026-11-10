package com.spendanalyzer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Name, email and phone rules, and that request objects never print secrets. */
class InputRulesTest {

	// ------------------------------------------------------------------ phone

	@ParameterizedTest
	@ValueSource(strings = { "9876543210", "09876543210", "919876543210", "+919876543210", "+91 98765 43210",
			"+91-98765-43210", " 98765 43210 ", "(+91) 98765-43210", "0 98765 43210", "91 9876543210" })
	void acceptedPhoneFormsAllBecomeTheSameStoredNumber(String typed) {
		assertThat(IndianPhone.normalise(typed)).contains("+919876543210");
	}

	@Test
	void theFirstDigitMustBeSixToNine() {
		assertThat(IndianPhone.normalise("6000000000")).contains("+916000000000");
		assertThat(IndianPhone.normalise("7000000000")).contains("+917000000000");
		assertThat(IndianPhone.normalise("8000000000")).contains("+918000000000");
		assertThat(IndianPhone.normalise("9999999999")).contains("+919999999999");
		assertThat(IndianPhone.normalise("5999999999")).isEmpty();
		assertThat(IndianPhone.normalise("0123456789")).isEmpty();
		// ten digits that happen to start with 91 are a number, not a country code
		assertThat(IndianPhone.normalise("9198765432")).contains("+919198765432");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "12345", "987654321", "98765432101", "+9876543210", "+1 9876543210",
			"+92 9876543210", "0919876543210", "+09876543210", "98765x3210", "9876543210;drop", "++919876543210",
			"91+9876543210", "９８７６５４３２１０", "+91 98765 43210 ext 5", "919876543210919876543210" })
	void otherPhoneInputIsRefused(String typed) {
		assertThat(IndianPhone.normalise(typed)).isEmpty();
	}

	@Test
	void nullPhoneIsRefused() {
		assertThat(IndianPhone.normalise(null)).isEmpty();
	}

	@Test
	void thePhoneIsMaskedToItsLastFourDigits() {
		assertThat(IndianPhone.mask("+919876543210")).isEqualTo("+91 ••••••3210");
		assertThat(IndianPhone.national("+919876543210")).isEqualTo("9876543210");
	}

	// ------------------------------------------------------------------ email

	@Test
	void emailIsTrimmedAndLowerCased() {
		assertThat(EmailAddress.normalise("  Bala.K+Spend@Example.COM ")).contains("bala.k+spend@example.com");
		assertThat(EmailAddress.normalise("a_b-c%d@mail.student.ac.in")).contains("a_b-c%d@mail.student.ac.in");
		assertThat(EmailAddress.localPart("bala.k@example.com")).isEqualTo("bala.k");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "plain", "@example.com", "a@", "a@b", "a@b.c", "a b@example.com", "a@exa mple.com",
			"a@@example.com", "a@b@example.com", ".a@example.com", "a.@example.com", "a..b@example.com",
			"a@-example.com", "a@example-.com", "a@.example.com", "a@example..com", "\"a\"@example.com",
			"a<script>@example.com", "a@example.com\r\nBcc: x@evil.example", "a\u0000@example.com", "ä@example.com",
			"a@exämple.com", "a,b@example.com", "a@example.com,b@example.com", "a@[127.0.0.1]" })
	void oddEmailInputIsRefused(String typed) {
		assertThat(EmailAddress.normalise(typed)).isEmpty();
	}

	@Test
	void emailLengthLimits() {
		String domain = "@example.com";
		assertThat(EmailAddress.normalise("a".repeat(64) + domain)).isPresent();
		assertThat(EmailAddress.normalise("a".repeat(65) + domain)).isEmpty();
		// 193 characters of domain leave room for a 61 character name within the 254 limit
		String longDomain = "@" + ("d".repeat(62) + ".").repeat(3) + "com";
		assertThat(EmailAddress.normalise("a".repeat(254 - longDomain.length()) + longDomain)).isPresent();
		assertThat(EmailAddress.normalise("a".repeat(255 - longDomain.length()) + longDomain)).isEmpty();
		assertThat(EmailAddress.normalise(null)).isEmpty();
	}

	@Test
	void emailIsMaskedForLogs() {
		assertThat(EmailAddress.mask("bala@gmail.com")).isEqualTo("b***@gmail.com");
		assertThat(EmailAddress.mask("nonsense")).isEqualTo("***");
		assertThat(EmailAddress.mask(null)).isEqualTo("***");
	}

	// ------------------------------------------------------------------ name

	@Test
	void nameIsTwoToSixtyCharacters() {
		assertThat(PersonName.normalise("  Bala   Vignesh ")).contains("Bala Vignesh");
		assertThat(PersonName.normalise("Jo")).contains("Jo");
		assertThat(PersonName.normalise("பாலா")).contains("பாலா");
		assertThat(PersonName.normalise("x".repeat(60))).isPresent();
		assertThat(PersonName.normalise("x".repeat(61))).isEmpty();
		assertThat(PersonName.normalise("J")).isEmpty();
		assertThat(PersonName.normalise("   ")).isEmpty();
		assertThat(PersonName.normalise("Bala\nVignesh")).isEmpty();
		assertThat(PersonName.normalise(null)).isEmpty();
	}

	// ------------------------------------------------------------------ secrets in toString

	@Test
	void requestObjectsNeverPrintPasswordsOrCodes() {
		String secret = "Mango#Tree42x";
		String code = "493817";
		assertThat(new AuthRequests.Register("Bala", "bala@example.com", "9876543210", secret).toString())
			.doesNotContain(secret)
			.doesNotContain("9876543210")
			.doesNotContain("bala@example.com");
		assertThat(new AuthRequests.Login("bala@example.com", secret).toString()).doesNotContain(secret);
		assertThat(new AuthRequests.VerifyRegistration("id", code).toString()).doesNotContain(code);
		assertThat(new AuthRequests.ResetConfirm("id", code, secret).toString()).doesNotContain(code).doesNotContain(secret);
		assertThat(new AuthRequests.ChangePassword(secret, "Other#Pass99x").toString()).doesNotContain(secret)
			.doesNotContain("Other#Pass99x");
		assertThat(new AuthRequests.DeleteAccount(secret).toString()).doesNotContain(secret);
		assertThat(new UserRow(java.util.UUID.randomUUID(), "bala@example.com", "Bala", "+919876543210", "{bcrypt}$2a$10$abc")
			.toString()).doesNotContain("$2a$").doesNotContain("9876543210");
	}

}
