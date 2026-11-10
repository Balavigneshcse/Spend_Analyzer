package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The mails are fixed texts: code, purpose, expiry, warning. No links, nothing typed by a user. */
class MailTemplatesTest {

	private static final String TO = "student@example.com";

	@Test
	void theRegistrationMailSaysWhatTheCodeIsFor() {
		EmailMessage mail = MailTemplates.registerCode(TO, "042917", 10);

		assertThat(mail.to()).isEqualTo(TO);
		assertThat(mail.kind()).isEqualTo("REGISTER_CODE");
		assertThat(mail.subject()).isEqualTo("042917 is your Spend Analyzer code");
		for (String content : List.of(mail.text(), mail.html())) {
			assertThat(content).contains("042917")
				.contains("to finish creating your Spend Analyzer account")
				.contains("valid for 10 minutes")
				.contains("Never share this code. If this was not you, ignore this email.");
		}
	}

	@Test
	void theResetMailSaysWhatTheCodeIsFor() {
		EmailMessage mail = MailTemplates.resetCode(TO, "550001", 10);

		assertThat(mail.kind()).isEqualTo("RESET_CODE");
		assertThat(mail.subject()).isEqualTo("550001 is your Spend Analyzer code");
		for (String content : List.of(mail.text(), mail.html())) {
			assertThat(content).contains("550001")
				.contains("to reset your Spend Analyzer password")
				.contains("valid for 10 minutes")
				.contains("Never share this code. If this was not you, ignore this email.")
				.doesNotContain("creating your");
		}
	}

	@Test
	void theNoticeHasNoCode() {
		EmailMessage mail = MailTemplates.accountExists(TO);

		assertThat(mail.kind()).isEqualTo("ACCOUNT_NOTICE");
		assertThat(mail.subject()).isEqualTo("You already have a Spend Analyzer account");
		assertThat(mail.text()).contains("already has one").contains("Forgot password").doesNotContainPattern("[0-9]{6}");
		assertThat(mail.html()).contains("already has one").doesNotContainPattern("[0-9]{6}");
	}

	@Test
	void noMailContainsALinkOrTheRecipient() {
		for (EmailMessage mail : List.of(MailTemplates.registerCode(TO, "123456", 10),
				MailTemplates.resetCode(TO, "123456", 10), MailTemplates.accountExists(TO))) {
			for (String content : List.of(mail.subject(), mail.text(), mail.html())) {
				assertThat(content).doesNotContainIgnoringCase("http")
					.doesNotContainIgnoringCase("www.")
					.doesNotContainIgnoringCase("href")
					.doesNotContainIgnoringCase("<a ")
					.doesNotContainIgnoringCase("<img")
					.doesNotContainIgnoringCase("<script")
					.doesNotContain(TO);
			}
		}
	}

	@Test
	void aMailNeverPrintsItsContent() {
		EmailMessage mail = MailTemplates.registerCode(TO, "042917", 10);
		assertThat(mail.toString()).isEqualTo("EmailMessage[kind=REGISTER_CODE, to=s***@example.com]");
		assertThat(mail.maskedTo()).isEqualTo("s***@example.com");
	}

}
