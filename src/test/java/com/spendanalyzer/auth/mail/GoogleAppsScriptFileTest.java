package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.spendanalyzer.auth.EmailAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Apps Script the owner pastes into script.google.com (deploy/google-apps-script/Code.gs) and
 * the Java sender must agree on limits, error words and addresses. The script cannot be run here;
 * this compares what both sides write down.
 */
class GoogleAppsScriptFileTest {

	private static String script;

	@BeforeAll
	static void readTheScript() throws IOException {
		Path file = Path.of("deploy", "google-apps-script", "Code.gs");
		assertThat(file).isRegularFile();
		script = Files.readString(file, StandardCharsets.UTF_8);
	}

	private static int constant(String name) {
		Matcher matcher = Pattern.compile("(?m)^const " + name + " = (\\d+);").matcher(script);
		assertThat(matcher.find()).as("const %s in Code.gs", name).isTrue();
		return Integer.parseInt(matcher.group(1));
	}

	@Test
	void theLimitsAreTheOnesTheSenderKnows() {
		assertThat(constant("MIN_SECRET")).isEqualTo(GoogleScriptEmailSender.MIN_SECRET_LENGTH);
		assertThat(constant("MAX_SUBJECT")).isEqualTo(GoogleScriptEmailSender.MAX_SUBJECT_LENGTH);
		assertThat(constant("MAX_TEXT")).isEqualTo(GoogleScriptEmailSender.MAX_TEXT_LENGTH);
		assertThat(constant("MAX_HTML")).isEqualTo(GoogleScriptEmailSender.MAX_HTML_LENGTH);
	}

	@Test
	void everyErrorWordOfTheScriptIsExplainedByTheSender() {
		Set<String> words = new TreeSet<>();
		Matcher matcher = Pattern.compile("'([a-z]+_[a-z]+|quota|unauthorized)'").matcher(script);
		while (matcher.find()) {
			words.add(matcher.group(1));
		}
		assertThat(words).containsExactly("bad_html", "bad_json", "bad_subject", "bad_text", "bad_to", "not_configured",
				"quota", "send_failed", "unauthorized");
		for (String word : words) {
			// a known word gets a hint in brackets, an unknown one would stand alone
			assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, "{\"ok\":false,\"error\":\"" + word + "\"}"))
				.isInstanceOf(EmailException.class)
				.hasMessageStartingWith("Google Apps Script did not send the mail: " + word + " (")
				.hasMessageEndingWith(")");
		}
	}

	@Test
	void theScriptReadsTheFieldsTheSenderSends() {
		for (String field : new String[] { "data.secret", "data.to", "data.subject", "data.text", "data.html",
				"data.fromName" }) {
			assertThat(script).contains(field);
		}
		assertThat(script).contains("function doPost(e)", "function doGet()", "function checkSetup()");
	}

	@Test
	void theSecretComesFromTheScriptPropertiesAndIsNotWrittenInTheFile() {
		assertThat(script).contains("PropertiesService.getScriptProperties().getProperty('SECRET')");
		// no line gives a secret a value in the code
		assertThat(script).doesNotContainPattern("(?im)^\\s*(const|let|var)\\s+secret\\s*=\\s*['\"]");
		// mail is sent in exactly one place, and only the narrow MailApp is used (not the whole mailbox)
		assertThat(script.split("MailApp\\.sendEmail\\(", -1)).hasSize(2);
		assertThat(script).doesNotContain("GmailApp");
	}

	@Test
	void theScriptAcceptsEveryAddressTheApplicationAccepts() {
		Matcher matcher = Pattern.compile("!/(\\^.+\\$)/\\.test\\(to\\)").matcher(script);
		assertThat(matcher.find()).as("the address rule in Code.gs").isTrue();
		// the rule uses nothing that JavaScript and Java read differently
		Pattern rule = Pattern.compile(matcher.group(1));

		for (String address : new String[] { "student@example.com", "first.last+tag@mail.example.co.in",
				"a_b%c-d@x-y.example.org", "9876543210@gmail.com", "a@b.cd" }) {
			assertThat(EmailAddress.normalise(address)).as(address).isPresent();
			assertThat(rule.matcher(address).matches()).as(address).isTrue();
		}
		// one address only: nothing that would make Gmail mail a second person
		for (String address : new String[] { "a@example.com,b@example.com", "a@example.com;b@example.com",
				"a@example.com b@example.com", "Name <a@example.com>", "a@example.com\nBcc: b@example.com", "a@@example.com",
				"a@example", "@example.com", "" }) {
			assertThat(rule.matcher(address).matches()).as(address).isFalse();
		}
	}

}
