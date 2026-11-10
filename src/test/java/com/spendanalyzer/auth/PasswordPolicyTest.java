package com.spendanalyzer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

	private static final String EMAIL = "bala.vignesh@example.com";

	private static final String PHONE = "+919876543210";

	private final PasswordPolicy policy = new PasswordPolicy();

	private Optional<String> problem(String password) {
		return policy.problem(password, EMAIL, PHONE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "Mango#Tree42x", "River!Stone73q", "aB3$efghij", "Tamil-Nadu 2026!", "zZ9_zZ9_zZ9_",
			"correct Horse 7 battery", "Ünïcödé#Pass12" })
	void goodPasswordsPass(String password) {
		assertThat(problem(password)).isEmpty();
	}

	@Test
	void sixtyFourCharactersAreAllowedSixtyFiveAreNot() {
		assertThat(problem("Aa1!" + "x".repeat(60))).isEmpty();
		assertThat(problem("Aa1!" + "x".repeat(61))).contains("Use at most 64 characters.");
	}

	@Test
	void tenCharactersAreAllowedNineAreNot() {
		assertThat(problem("Aa1!xyzxyz")).isEmpty();
		assertThat(problem("Aa1!xyzxy")).contains("Use at least 10 characters.");
		assertThat(problem("")).contains("Enter a password.");
		assertThat(problem(null)).contains("Enter a password.");
	}

	@Test
	void everyCharacterClassIsRequired() {
		assertThat(problem("mango#tree42x")).contains("Add an upper-case letter.");
		assertThat(problem("MANGO#TREE42X")).contains("Add a lower-case letter.");
		assertThat(problem("Mango#Treexyz")).contains("Add a digit.");
		assertThat(problem("MangoTree42xy")).contains("Add a symbol such as @ or #.");
		assertThat(problem("mangotreexyz")).contains("Add an upper-case letter, a digit, a symbol such as @ or #.");
		assertThat(problem("1234567890123")).get().asString().startsWith("Add a lower-case letter, an upper-case letter");
	}

	@Test
	void seventyThreeBytesAreRefusedEvenWhenTheCharacterCountIsFine() {
		// 4 + 23 * 3 = 73 bytes in 27 characters
		String seventyThree = "Aa1!" + "€".repeat(23);
		assertThat(seventyThree.getBytes(StandardCharsets.UTF_8)).hasSize(73);
		assertThat(problem(seventyThree)).contains("That password is too long for the letters it uses. Make it shorter.");

		String seventyTwo = "Aa1!x" + "€".repeat(22) + "y";
		assertThat(seventyTwo.getBytes(StandardCharsets.UTF_8)).hasSize(72);
		assertThat(problem(seventyTwo)).isEmpty();
	}

	@Test
	void theEmailNameMustNotBeInThePassword() {
		assertThat(problem("Xy1#bala.vignesh")).contains("Do not put your email name in the password.");
		assertThat(problem("Xy1#BALA.Vignesh!!")).contains("Do not put your email name in the password.");
		// only the full name part counts, not pieces of it
		assertThat(problem("Xy1#bala-and-more")).isEmpty();
		// very short names are ignored: they would forbid ordinary words
		assertThat(policy.problem("Mango#Tree42x", "an@example.com", PHONE)).isEmpty();
		assertThat(policy.problem("Mango#Tree42x", "man@example.com", PHONE))
			.contains("Do not put your email name in the password.");
	}

	@Test
	void thePhoneNumberMustNotBeInThePassword() {
		assertThat(problem("Aa#9876543210")).contains("Do not put your phone number in the password.");
		assertThat(problem("Aa#+919876543210")).contains("Do not put your phone number in the password.");
		assertThat(problem("Aa#9876543211x")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Password@123", "password@123", "PASSWORD@123", "pAsSwOrD@123", "Welcome@123", "Admin@1234",
			"India@12345", "Qwerty@123", "P@ssw0rd123", "Iloveyou@123", "Krishna@123", "Sachin@1234", "Welcome@2026",
			"1qaz@WSX3edc", "Test@12345" })
	void wellKnownPasswordsAreRefusedWhateverTheCase(String password) {
		assertThat(policy.problem(password, "someone@example.com", PHONE))
			.contains("That password is too well known. Choose another one.");
	}

	@Test
	void aPasswordThatOnlyResemblesAWellKnownOneIsJudgedByTheNormalRule() {
		assertThat(policy.problem("Abcd@1234!x", "someone@example.com", PHONE)).isEmpty();
	}

	@Test
	void theListHasAFewHundredEntriesIncludingTheIndianFavourites() {
		for (String favourite : new String[] { "India@123", "Password@123", "Welcome@123", "Admin@1234" }) {
			// India@123 is too short anyway; the others must be caught by the list
			Optional<String> problem = policy.problem(favourite);
			assertThat(problem).as(favourite).isPresent();
		}
		assertThat(policy.problem("Welcome@123")).contains("That password is too well known. Choose another one.");
		assertThat(policy.problem("Admin@1234")).contains("That password is too well known. Choose another one.");
	}

	@Test
	void controlCharactersAreRefused() {
		assertThat(problem("Mango#Tree42x\n")).contains("The password contains a character that cannot be used.");
		assertThat(problem("Mango#\u0000Tree42x")).contains("The password contains a character that cannot be used.");
	}

	@Test
	void thePersonalCheckAloneToleratesMissingData() {
		assertThat(policy.personalProblem("Aa#9876543210", null, null)).isEmpty();
		assertThat(policy.personalProblem("Aa#9876543210", EMAIL, null)).isEmpty();
		assertThat(policy.personalProblem("Aa#9876543210", null, PHONE)).isPresent();
		assertThat(policy.personalProblem(null, EMAIL, PHONE)).isEmpty();
	}

}
