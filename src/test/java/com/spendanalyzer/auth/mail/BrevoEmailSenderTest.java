package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the Brevo sender puts on the wire and how it judges the answer. No network needed. */
class BrevoEmailSenderTest {

	private static final EmailMessage MAIL = MailTemplates.registerCode("student@example.com", "493817", 10);

	private final BrevoEmailSender sender = new BrevoEmailSender("https://brevo.invalid/v3/smtp/email",
			" xkeysib-test-key ", "noreply@example.com", "Spend \"Analyzer\"");

	@Test
	void theRequestIsWhatBrevoExpects() {
		HttpRequest request = sender.request(MAIL);

		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.uri().toString()).isEqualTo("https://brevo.invalid/v3/smtp/email");
		assertThat(request.timeout()).contains(Duration.ofSeconds(8));
		assertThat(request.headers().map()).containsOnlyKeys("api-key", "accept", "content-type");
		assertThat(request.headers().firstValue("api-key")).contains("xkeysib-test-key");
		assertThat(request.headers().firstValue("accept")).contains("application/json");
		assertThat(request.headers().firstValue("content-type")).contains("application/json");
		assertThat(request.bodyPublisher()).isPresent();
		assertThat(request.bodyPublisher().get().contentLength())
			.isEqualTo(sender.body(MAIL).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
	}

	@Test
	void theBodyIsTheJsonBrevoExpects() {
		JsonNode body = JsonMapper.builder().build().readTree(sender.body(MAIL));

		assertThat(body.properties()).extracting(Map.Entry::getKey)
			.containsExactly("sender", "to", "subject", "textContent", "htmlContent");
		// the quote in the configured name survives as proper JSON
		assertThat(body.get("sender").get("name").asString()).isEqualTo("Spend \"Analyzer\"");
		assertThat(body.get("sender").get("email").asString()).isEqualTo("noreply@example.com");
		assertThat(body.get("to")).hasSize(1);
		assertThat(body.get("to").get(0).properties()).hasSize(1);
		assertThat(body.get("to").get(0).get("email").asString()).isEqualTo("student@example.com");
		assertThat(body.get("subject").asString()).isEqualTo("493817 is your Spend Analyzer code");
		assertThat(body.get("textContent").asString()).isEqualTo(MAIL.text());
		assertThat(body.get("htmlContent").asString()).isEqualTo(MAIL.html());
	}

	@Test
	void theTimeoutsAreShort() {
		assertThat(BrevoEmailSender.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
		assertThat(BrevoEmailSender.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(8));
	}

	@Test
	void anyTwoHundredAnswerIsSuccess() {
		for (int status : new int[] { 200, 201, 202, 204 }) {
			assertThatCode(() -> BrevoEmailSender.requireAccepted(status, "{\"messageId\":\"<abc@relay>\"}"))
				.doesNotThrowAnyException();
		}
	}

	@Test
	void aRefusalIsReportedWithStatusAndAnswer() {
		assertThatThrownBy(
				() -> BrevoEmailSender.requireAccepted(401, "{\"message\":\"Key not found\",\n \"code\":\"unauthorized\"}"))
			.isInstanceOf(EmailException.class)
			.hasMessage("Brevo answered HTTP 401: {\"message\":\"Key not found\", \"code\":\"unauthorized\"}");
		for (int status : new int[] { 199, 301, 400, 403, 429, 500, 503 }) {
			assertThatThrownBy(() -> BrevoEmailSender.requireAccepted(status, null)).isInstanceOf(EmailException.class)
				.hasMessage("Brevo answered HTTP " + status + ": ");
		}
	}

	@Test
	void aLongErrorAnswerIsCutShort() {
		assertThatThrownBy(() -> BrevoEmailSender.requireAccepted(500, "x".repeat(5000)))
			.isInstanceOf(EmailException.class)
			.satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(600));
	}

	@Test
	void keyAndSenderAddressAreRequired() {
		assertThatThrownBy(() -> new BrevoEmailSender(BrevoEmailSender.DEFAULT_URL, " ", "a@example.com", "A"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("BREVO_API_KEY");
		assertThatThrownBy(() -> new BrevoEmailSender(BrevoEmailSender.DEFAULT_URL, "key", "", "A"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MAIL_FROM_EMAIL");
	}

	@Test
	void theRealEndpointIsTheDefault() {
		assertThat(BrevoEmailSender.DEFAULT_URL).isEqualTo("https://api.brevo.com/v3/smtp/email");
	}

}
