package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * What the Google Apps Script sender puts on the wire, how it follows Google's redirect and how it
 * judges the answer. No network needed.
 */
class GoogleScriptEmailSenderTest {

	private static final EmailMessage MAIL = MailTemplates.registerCode("student@example.com", "493817", 10);

	private static final String DEPLOYMENT = "AKfycbTestDeployment_0123456789-abc";

	private static final String URL = "https://script.google.com/macros/s/" + DEPLOYMENT + "/exec";

	private static final String SECRET = "test-secret-0123456789abcdef0123456789";

	/** Where Google really keeps the answer of a script. */
	private static final String ANSWER_URL = "https://script.googleusercontent.com/macros/echo?user_content_key=one-time-key";

	private static final String SENT = "{\"ok\":true}";

	private final GoogleScriptEmailSender sender = new GoogleScriptEmailSender(" " + URL + " ", " " + SECRET + " ",
			"Spend \"Analyzer\"");

	// ------------------------------------------------------------------ the request

	@Test
	void theRequestIsWhatTheScriptExpects() {
		HttpRequest request = sender.request(MAIL);

		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.uri().toString()).isEqualTo(URL);
		assertThat(request.timeout()).contains(Duration.ofSeconds(40));
		// the script only gets the body when the content type is JSON
		assertThat(request.headers().map()).containsOnlyKeys("content-type");
		assertThat(request.headers().firstValue("content-type")).contains("application/json");
		assertThat(request.bodyPublisher()).isPresent();
		assertThat(request.bodyPublisher().get().contentLength())
			.isEqualTo(sender.body(MAIL).getBytes(StandardCharsets.UTF_8).length);
	}

	@Test
	void theSecretTravelsInTheBodyOnly() {
		HttpRequest request = sender.request(MAIL);

		// addresses and headers end up in the logs of proxies: neither may carry the secret
		assertThat(request.uri().toString()).doesNotContain(SECRET);
		assertThat(request.uri().getQuery()).isNull();
		assertThat(request.headers().map().toString()).doesNotContain(SECRET);
		assertThat(sender.body(MAIL)).contains(SECRET);
		assertThat(sender.toString()).doesNotContain(SECRET).doesNotContain(DEPLOYMENT);
	}

	@Test
	void theBodyIsTheJsonTheScriptExpects() {
		JsonNode body = JsonMapper.builder().build().readTree(sender.body(MAIL));

		assertThat(body.properties()).extracting(Map.Entry::getKey)
			.containsExactly("secret", "to", "subject", "text", "html", "fromName");
		// the configured secret is sent without the spaces around it
		assertThat(body.get("secret").asString()).isEqualTo(SECRET);
		assertThat(body.get("to").asString()).isEqualTo("student@example.com");
		assertThat(body.get("subject").asString()).isEqualTo("493817 is your Spend Analyzer code");
		assertThat(body.get("text").asString()).isEqualTo(MAIL.text());
		assertThat(body.get("html").asString()).isEqualTo(MAIL.html());
		// the quote in the configured name survives as proper JSON
		assertThat(body.get("fromName").asString()).isEqualTo("Spend \"Analyzer\"");
	}

	@Test
	void aMissingSenderNameGetsTheDefault() {
		GoogleScriptEmailSender unnamed = new GoogleScriptEmailSender(URL, SECRET, "  ");
		assertThat(JsonMapper.builder().build().readTree(unnamed.body(MAIL)).get("fromName").asString())
			.isEqualTo("Spend Analyzer");
	}

	@Test
	void theTimeoutsLeaveRoomForAColdStartAndASlowScript() {
		assertThat(GoogleScriptEmailSender.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(15));
		assertThat(GoogleScriptEmailSender.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(40));
	}

	@Test
	void everyMailOfTheApplicationFitsTheLimitsOfTheScript() {
		for (EmailMessage mail : List.of(MAIL, MailTemplates.resetCode("student@example.com", "493817", 10),
				MailTemplates.accountExists("student@example.com"))) {
			assertThat(mail.subject()).isNotEmpty()
				.hasSizeLessThanOrEqualTo(GoogleScriptEmailSender.MAX_SUBJECT_LENGTH)
				.doesNotContain("\r", "\n");
			assertThat(mail.text()).isNotEmpty().hasSizeLessThanOrEqualTo(GoogleScriptEmailSender.MAX_TEXT_LENGTH);
			assertThat(mail.html()).hasSizeLessThanOrEqualTo(GoogleScriptEmailSender.MAX_HTML_LENGTH);
		}
	}

	// ------------------------------------------------------------------ judging the answer

	@Test
	void onlyTwoHundredWithOkTrueIsSuccess() {
		assertThatCode(() -> GoogleScriptEmailSender.requireSent(200, SENT)).doesNotThrowAnyException();
		assertThatCode(() -> GoogleScriptEmailSender.requireSent(200, " { \"ok\" : true }\n")).doesNotThrowAnyException();
	}

	@Test
	void anyOtherStatusIsAFailureEvenWithOkTrue() {
		for (int status : new int[] { 199, 201, 202, 204, 307, 308, 400, 401, 403, 404, 405, 429, 500, 503 }) {
			assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(status, SENT)).isInstanceOf(EmailException.class)
				.hasMessageStartingWith("Google Apps Script answered HTTP " + status);
		}
	}

	@Test
	void aStatusComesWithAHintWhatToLookAt() {
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(404, "<html>Not found</html>"))
			.hasMessage("Google Apps Script answered HTTP 404 (GOOGLE_SCRIPT_URL is wrong, or the web app deployment "
					+ "was removed)");
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(403, null)).hasMessageContaining("\"Anyone\"");
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(401, null)).hasMessageContaining("\"Anyone\"");
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(429, null)).hasMessageContaining("temporary");
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(502, null)).hasMessageContaining("temporary");
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(400, null))
			.hasMessage("Google Apps Script answered HTTP 400");
	}

	@Test
	void twoHundredWithAPageInsteadOfJsonIsAFailure() {
		// Google's sign-in page and its "Authorization needed" page both come with status 200
		for (String page : new String[] { "<!DOCTYPE html><html><body>Sign in - Google Accounts</body></html>",
				"Authorization needed", "", "   ", null, "{\"ok\":tru", "{\"ok\":true", "ok", "true", "1", "[]",
				"[{\"ok\":true}]", "\"ok\"", "null" }) {
			assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, page)).as("answer: %s", page)
				.isInstanceOf(EmailException.class)
				.hasMessageContaining("HTTP 200")
				.hasMessageContaining("not_json");
		}
	}

	@Test
	void okMustBeTheBooleanTrue() {
		for (String answer : new String[] { "{}", "{\"ok\":false}", "{\"ok\":\"true\"}", "{\"ok\":1}", "{\"ok\":null}",
				"{\"OK\":true}", "{\"sent\":true}" }) {
			assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, answer)).as("answer: %s", answer)
				.isInstanceOf(EmailException.class)
				.hasMessage("Google Apps Script did not send the mail: unknown_error");
		}
	}

	@Test
	void theErrorWordOfTheScriptIsReportedWithAHint() {
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, refused("unauthorized")))
			.hasMessage("Google Apps Script did not send the mail: unauthorized "
					+ "(the script property SECRET and GOOGLE_SCRIPT_SECRET are not the same)");
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, refused("not_configured")))
			.hasMessageContaining("not_configured")
			.hasMessageContaining("script property SECRET of at least 32 characters");
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, refused("quota")))
			.hasMessageContaining("quota")
			.hasMessageContaining("24 hours");
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, refused("send_failed")))
			.hasMessage("Google Apps Script did not send the mail: send_failed "
					+ "(Gmail refused the mail; run checkSetup in the script editor)");
		for (String word : new String[] { "bad_json", "bad_to", "bad_subject", "bad_text", "bad_html" }) {
			assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, refused(word)))
				.hasMessageContaining(": " + word + " ")
				.hasMessageContaining("Code.gs");
		}
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, refused("something_new")))
			.hasMessage("Google Apps Script did not send the mail: something_new");
	}

	@Test
	void anythingElseInPlaceOfTheErrorWordIsNotRepeated() {
		// whatever the other side writes there, only a short plain word may reach the log
		for (String answer : new String[] { "{\"ok\":false,\"error\":\"Invalid email: student@example.com\"}",
				"{\"ok\":false,\"error\":\"493817\"}", "{\"ok\":false,\"error\":\"" + "x".repeat(31) + "\"}",
				"{\"ok\":false,\"error\":{\"message\":\"boom\"}}", "{\"ok\":false,\"error\":12}",
				"{\"ok\":false,\"error\":\"\"}" }) {
			assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, answer)).as("answer: %s", answer)
				.hasMessage("Google Apps Script did not send the mail: unknown_error");
		}
	}

	@Test
	void aLongPageIsNeitherParsedNorRepeated() {
		String page = "<html>" + "student@example.com 493817 ".repeat(5000) + "</html>";
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, page)).isInstanceOf(EmailException.class)
			.hasMessageContaining("not_json")
			.hasMessageNotContaining("493817")
			.satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(300));
		// even a correct answer hidden behind a lot of padding is not what the script sends
		assertThatThrownBy(() -> GoogleScriptEmailSender.requireSent(200, " ".repeat(5000) + SENT))
			.hasMessageContaining("not_json");
	}

	// ------------------------------------------------------------------ the redirect to the answer

	@Test
	void theAnswerIsFetchedWithGetFromWhereGooglePointsTo() {
		for (int status : new int[] { 302, 303, 301 }) {
			ScriptedSender scripted = new ScriptedSender(redirect(status, ANSWER_URL), answer(200, SENT));

			assertThatCode(() -> scripted.send(MAIL)).doesNotThrowAnyException();

			assertThat(scripted.requests).hasSize(2);
			HttpRequest post = scripted.requests.get(0);
			assertThat(post.method()).isEqualTo("POST");
			assertThat(post.uri().toString()).isEqualTo(URL);
			HttpRequest get = scripted.requests.get(1);
			// a POST to that address is answered with 405: it must be a GET, without body and secret
			assertThat(get.method()).isEqualTo("GET");
			assertThat(get.uri().toString()).isEqualTo(ANSWER_URL);
			assertThat(get.bodyPublisher()).isEmpty();
			assertThat(get.headers().map()).isEmpty();
			assertThat(get.timeout()).contains(Duration.ofSeconds(40));
		}
	}

	@Test
	void theAnswerBehindTheRedirectDecides() {
		ScriptedSender refused = new ScriptedSender(redirect(302, ANSWER_URL), answer(200, refused("unauthorized")));
		assertThatThrownBy(() -> refused.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageContaining("unauthorized");

		ScriptedSender page = new ScriptedSender(redirect(302, ANSWER_URL), answer(200, "<html>Sign in</html>"));
		assertThatThrownBy(() -> page.send(MAIL)).isInstanceOf(EmailException.class).hasMessageContaining("not_json");

		ScriptedSender gone = new ScriptedSender(redirect(302, ANSWER_URL), answer(404, SENT));
		assertThatThrownBy(() -> gone.send(MAIL)).isInstanceOf(EmailException.class).hasMessageContaining("HTTP 404");
	}

	@Test
	void aRedirectThatWouldPostTheBodyAgainIsNotFollowed() {
		// 307 and 308 mean "repeat the POST there": the secret must never reach a second address
		for (int status : new int[] { 307, 308 }) {
			ScriptedSender scripted = new ScriptedSender(redirect(status, ANSWER_URL), answer(200, SENT));

			assertThatThrownBy(() -> scripted.send(MAIL)).isInstanceOf(EmailException.class)
				.hasMessage("Google Apps Script answered HTTP " + status);
			assertThat(scripted.requests).hasSize(1);
		}
	}

	@Test
	void aRedirectToAnotherHostIsNotFollowed() {
		ScriptedSender signIn = new ScriptedSender(
				redirect(302, "https://accounts.google.com/ServiceLogin?continue=https://script.google.com/x"),
				answer(200, SENT));
		assertThatThrownBy(() -> signIn.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessage("Google Apps Script answered HTTP 302 and pointed to accounts.google.com instead of the "
					+ "script's answer: Google asks for a sign-in, so \"Who has access\" of the web app is not \"Anyone\"");
		assertThat(signIn.requests).hasSize(1);

		for (String elsewhere : new String[] { "https://example.com/macros/echo?user_content_key=k",
				"https://script.googleusercontent.com.example.com/macros/echo",
				"https://notgoogleusercontent.com/macros/echo",
				// never from HTTPS down to plain HTTP
				"http://script.googleusercontent.com/macros/echo?user_content_key=k",
				"http://script.google.com/macros/s/" + DEPLOYMENT + "/exec", "ftp://script.googleusercontent.com/x" }) {
			ScriptedSender scripted = new ScriptedSender(redirect(302, elsewhere), answer(200, SENT));

			assertThatThrownBy(() -> scripted.send(MAIL)).as(elsewhere)
				.isInstanceOf(EmailException.class)
				.hasMessageContaining("HTTP 302 and pointed to ")
				.hasMessageNotContaining("user_content_key")
				.hasMessageNotContaining(DEPLOYMENT);
			assertThat(scripted.requests).as(elsewhere).hasSize(1);
		}
	}

	@Test
	void aRedirectWithinGoogleIsFollowed() {
		// a path on the web app's own host, and any host under googleusercontent.com over HTTPS
		for (String target : new String[] { "/macros/echo?user_content_key=k",
				"https://script.google.com/macros/echo?user_content_key=k",
				"https://SCRIPT.googleusercontent.com/macros/echo?user_content_key=k",
				"https://script-2.googleusercontent.com/macros/echo?user_content_key=k" }) {
			ScriptedSender scripted = new ScriptedSender(redirect(302, target), answer(200, SENT));

			assertThatCode(() -> scripted.send(MAIL)).as(target).doesNotThrowAnyException();
			assertThat(scripted.requests).hasSize(2);
			assertThat(scripted.requests.get(1).uri().getScheme()).isEqualTo("https");
		}
	}

	@Test
	void aRedirectWithoutAUsableAddressIsAFailure() {
		for (String location : new String[] { null, "", "  ", "https://exa mple.com/ x", "mailto:someone@example.com" }) {
			ScriptedSender scripted = new ScriptedSender(redirect(302, location), answer(200, SENT));

			assertThatThrownBy(() -> scripted.send(MAIL)).as("location: %s", location)
				.isInstanceOf(EmailException.class)
				.hasMessage("Google Apps Script answered HTTP 302 without an address to go to");
			assertThat(scripted.requests).hasSize(1);
		}
	}

	@Test
	void aLoopOfRedirectsEnds() {
		ScriptedSender scripted = new ScriptedSender(redirect(302, ANSWER_URL), redirect(302, ANSWER_URL),
				redirect(302, ANSWER_URL), redirect(302, ANSWER_URL), answer(200, SENT));

		assertThatThrownBy(() -> scripted.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessage("Google Apps Script redirected more than 3 times");
		// the POST and three GETs, never a second POST
		assertThat(scripted.requests).extracting(HttpRequest::method).containsExactly("POST", "GET", "GET", "GET");
	}

	// ------------------------------------------------------------------ what is tried again

	@Test
	void aFailedConnectionIsTriedOnceMore() {
		for (IOException failure : List.of(new ConnectException("refused"),
				new HttpConnectTimeoutException("HTTP connect timed out"))) {
			ScriptedSender scripted = new ScriptedSender(failure, redirect(302, ANSWER_URL), answer(200, SENT));

			assertThatCode(() -> scripted.send(MAIL)).doesNotThrowAnyException();
			assertThat(scripted.requests).extracting(HttpRequest::method).containsExactly("POST", "POST", "GET");
		}
	}

	@Test
	void aConnectionThatFailsTwiceIsGivenUp() {
		ScriptedSender scripted = new ScriptedSender(new ConnectException("refused"),
				new HttpConnectTimeoutException("HTTP connect timed out"), answer(200, SENT));

		assertThatThrownBy(() -> scripted.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessage("Google Apps Script could not be reached: HttpConnectTimeoutException")
			.hasNoCause();
		// one retry, not a loop
		assertThat(scripted.requests).hasSize(2);
	}

	@Test
	void aFailureAfterTheConnectionIsNotRetried() {
		// the script runs before it answers: sending the request again could mail the code twice
		ScriptedSender slow = new ScriptedSender(new HttpTimeoutException("request timed out"), answer(200, SENT));
		assertThatThrownBy(() -> slow.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessage("Google Apps Script did not answer in time (the mail may still have been sent)")
			.hasNoCause();
		assertThat(slow.requests).hasSize(1);

		ScriptedSender broken = new ScriptedSender(new IOException("connection reset"), answer(200, SENT));
		assertThatThrownBy(() -> broken.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessage("Google Apps Script could not be reached: IOException (the mail may still have been sent)")
			.hasNoCause();
		assertThat(broken.requests).hasSize(1);
	}

	@Test
	void aRefusalOrAnErrorOfGoogleIsNotRetried() {
		for (HttpResponse<String> first : List.of(answer(200, refused("send_failed")), answer(500, "<html>Error</html>"),
				answer(503, ""), answer(429, ""), answer(200, "<html>Sign in</html>"))) {
			ScriptedSender scripted = new ScriptedSender(first, answer(200, SENT));

			assertThatThrownBy(() -> scripted.send(MAIL)).isInstanceOf(EmailException.class);
			assertThat(scripted.requests).hasSize(1);
		}
	}

	@Test
	void aFailedConnectionToTheAnswerIsReadAgainButNeverPostedAgain() {
		for (IOException failure : List.of(new ConnectException("refused"),
				new HttpConnectTimeoutException("HTTP connect timed out"))) {
			ScriptedSender scripted = new ScriptedSender(redirect(302, ANSWER_URL), failure, answer(200, SENT));

			assertThatCode(() -> scripted.send(MAIL)).doesNotThrowAnyException();
			// by now the script has run: only the reading is repeated
			assertThat(scripted.requests).extracting(HttpRequest::method).containsExactly("POST", "GET", "GET");
		}
	}

	@Test
	void anAnswerThatCannotBeReadIsAFailureWithoutASecondPost() {
		for (IOException failure : List.of(new HttpTimeoutException("request timed out"),
				new IOException("connection reset"))) {
			ScriptedSender scripted = new ScriptedSender(redirect(302, ANSWER_URL), failure, answer(200, SENT));

			assertThatThrownBy(() -> scripted.send(MAIL)).isInstanceOf(EmailException.class)
				.hasMessageStartingWith("Google Apps Script took the request but its answer could not be read: ")
				.hasMessageEndingWith("(the mail may have been sent)")
				.hasNoCause();
			assertThat(scripted.requests).extracting(HttpRequest::method).containsExactly("POST", "GET");
		}

		ScriptedSender unreachable = new ScriptedSender(redirect(302, ANSWER_URL), new ConnectException("refused"),
				new ConnectException("refused"), answer(200, SENT));
		assertThatThrownBy(() -> unreachable.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageContaining("its answer could not be read: ConnectException");
		assertThat(unreachable.requests).extracting(HttpRequest::method).containsExactly("POST", "GET", "GET");
	}

	// ------------------------------------------------------------------ nothing secret in the log

	/** Every way a mail can fail, as the outcomes of the round trips. */
	private static List<Object[]> everyFailure() {
		String echo = "<html>" + SECRET + " student@example.com 493817 " + MAIL.subject() + " " + URL + "</html>";
		return List.of(new Object[] { answer(200, refused("unauthorized")) }, new Object[] { answer(200, echo) },
				new Object[] { answer(200, "{\"ok\":false,\"error\":\"" + SECRET + "\"}") },
				new Object[] { answer(200, "{\"ok\":false,\"error\":\"student@example.com 493817\"}") },
				new Object[] { answer(404, echo) }, new Object[] { answer(500, echo) },
				new Object[] { answer(307, echo) },
				new Object[] { redirect(302, "https://example.com/?secret=" + SECRET + "&to=student@example.com") },
				new Object[] { redirect(302, "https://student@example.com/493817") },
				new Object[] { redirect(302, null) }, new Object[] { redirect(302, ANSWER_URL), answer(200, echo) },
				new Object[] { redirect(302, ANSWER_URL), new IOException(echo) },
				new Object[] { redirect(302, ANSWER_URL), redirect(302, ANSWER_URL), redirect(302, ANSWER_URL),
						redirect(302, ANSWER_URL) },
				new Object[] { new IOException(echo) }, new Object[] { new HttpTimeoutException(echo) },
				new Object[] { new ConnectException(echo), new ConnectException(echo) });
	}

	@Test
	void noFailureTextContainsTheSecretTheCodeTheRecipientOrTheAddress() {
		for (Object[] outcomes : everyFailure()) {
			ScriptedSender scripted = new ScriptedSender(outcomes);

			assertThatThrownBy(() -> scripted.send(MAIL)).isInstanceOf(EmailException.class)
				.hasNoCause()
				.hasMessageNotContaining(SECRET)
				.hasMessageNotContaining("493817")
				.hasMessageNotContaining("student@example.com")
				.hasMessageNotContaining("student")
				.hasMessageNotContaining(DEPLOYMENT)
				.hasMessageNotContaining("user_content_key")
				.satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(300));
		}
	}

	@Test
	void theLogOfAFailedMailNamesStatusAndErrorWordOnly() {
		Logger logger = (Logger) LoggerFactory.getLogger(MailDispatcher.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			for (Object[] outcomes : everyFailure()) {
				StaticListableBeanFactory beans = new StaticListableBeanFactory();
				beans.addBean("emailSender", new ScriptedSender(outcomes));
				MailDispatcher dispatcher = new MailDispatcher(beans.getBeanProvider(EmailSender.class),
						new MailStartupCheck(new MockEnvironment().withProperty("app.security.otp-pepper", "test-pepper")));
				try {
					dispatcher.send(MAIL);
					assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
				}
				finally {
					dispatcher.shutdown();
				}
			}
		}
		finally {
			logger.detachAppender(appender);
		}

		List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		assertThat(lines).hasSize(everyFailure().size());
		assertThat(lines).allSatisfy(line -> assertThat(line)
			.startsWith("Could not send REGISTER_CODE mail to s***@example.com: Google Apps Script ")
			.doesNotContain(SECRET)
			.doesNotContain("493817")
			.doesNotContain("student")
			.doesNotContain(DEPLOYMENT));
		assertThat(lines.get(0)).endsWith("did not send the mail: unauthorized "
				+ "(the script property SECRET and GOOGLE_SCRIPT_SECRET are not the same)");
		assertThat(lines.get(4)).contains("answered HTTP 404");
		// nothing of a failure carries a stack trace that could hold the request
		assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
	}

	// ------------------------------------------------------------------ configuration

	@Test
	void onlyAWebAppOfScriptGoogleComIsAccepted() {
		assertThat(GoogleScriptEmailSender.urlProblem(URL)).isNull();
		assertThat(GoogleScriptEmailSender.urlProblem("  " + URL + "\n")).isNull();
		assertThat(GoogleScriptEmailSender.urlProblem("https://script.google.com/macros/s/AKfycb-x_Y9/exec")).isNull();

		assertThat(GoogleScriptEmailSender.urlProblem(null)).isEqualTo("is not set");
		assertThat(GoogleScriptEmailSender.urlProblem("")).isEqualTo("is not set");
		assertThat(GoogleScriptEmailSender.urlProblem("   ")).isEqualTo("is not set");
		assertThat(GoogleScriptEmailSender.urlProblem("https://script.google.com/macros/s/" + DEPLOYMENT + "/dev"))
			.contains("/exec")
			.contains("/dev");
		for (String wrong : new String[] { "http://script.google.com/macros/s/" + DEPLOYMENT + "/exec",
				"https://script.google.com.example.com/macros/s/" + DEPLOYMENT + "/exec",
				"https://example.com/macros/s/" + DEPLOYMENT + "/exec",
				"https://example.com/?x=https://script.google.com/macros/s/" + DEPLOYMENT + "/exec",
				"https://script.google.com@example.com/macros/s/" + DEPLOYMENT + "/exec",
				"https://script.google.com:8443/macros/s/" + DEPLOYMENT + "/exec",
				"https://script.google.com/macros/s/" + DEPLOYMENT + "/exec/",
				"https://script.google.com/macros/s/" + DEPLOYMENT + "/exec?secret=x",
				"https://script.google.com/macros/s/" + DEPLOYMENT + "/exec#x",
				"https://script.google.com/macros/s//exec", "https://script.google.com/macros/s/a/b/exec",
				"https://script.google.com/macros/s/a b/exec", "https://script.google.com/macros/d/" + DEPLOYMENT + "/edit",
				"https://script.google.com/home/projects/" + DEPLOYMENT + "/edit", "HTTPS://SCRIPT.GOOGLE.COM/macros/s/x/exec",
				"script.google.com/macros/s/" + DEPLOYMENT + "/exec", DEPLOYMENT, "none" }) {
			assertThat(GoogleScriptEmailSender.urlProblem(wrong)).as(wrong)
				.startsWith("must look like https://script.google.com/macros/s/.../exec")
				.doesNotContain(DEPLOYMENT);
		}
	}

	@Test
	void aWebAppOfASchoolOrCompanyAccountIsRefusedWithItsOwnMessage() {
		String problem = "is the URL of a school or company Google account; "
				+ "make the script with a personal @gmail.com account";
		String workspace = "https://script.google.com/a/macros/college.example.edu/s/" + DEPLOYMENT;
		// refused, and neither the deployment nor the organisation is repeated
		assertThat(GoogleScriptEmailSender.urlProblem(workspace + "/exec")).isEqualTo(problem);
		assertThat(GoogleScriptEmailSender.urlProblem(workspace + "/dev")).isEqualTo(problem);
		assertThat(GoogleScriptEmailSender.urlProblem("  " + workspace + "/exec\n")).isEqualTo(problem);
		assertThatThrownBy(() -> new GoogleScriptEmailSender(workspace + "/exec", SECRET, "A"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("app.mail.provider=google: app.mail.google.script-url (GOOGLE_SCRIPT_URL) " + problem);
		// only on the real host: the same path somewhere else is just a wrong address
		assertThat(GoogleScriptEmailSender
			.urlProblem("https://example.com/a/macros/college.example.edu/s/" + DEPLOYMENT + "/exec"))
			.startsWith("must look like");
	}

	@Test
	void aWrongAddressStopsTheStartWithoutRepeatingIt() {
		assertThatThrownBy(() -> new GoogleScriptEmailSender("", SECRET, "A")).isInstanceOf(IllegalStateException.class)
			.hasMessage("app.mail.provider=google: app.mail.google.script-url (GOOGLE_SCRIPT_URL) is not set");
		assertThatThrownBy(() -> new GoogleScriptEmailSender((String) null, SECRET, "A"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("GOOGLE_SCRIPT_URL");
		assertThatThrownBy(() -> new GoogleScriptEmailSender("https://example.com/" + DEPLOYMENT + "/exec", SECRET, "A"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("GOOGLE_SCRIPT_URL")
			.hasMessageContaining("must look like")
			.hasMessageNotContaining(DEPLOYMENT)
			.hasMessageNotContaining("example.com");
	}

	@Test
	void theSecretIsRequiredAndMustBeLong() {
		assertThat(GoogleScriptEmailSender.MIN_SECRET_LENGTH).isEqualTo(32);
		assertThat(GoogleScriptEmailSender.secretProblem(SECRET)).isNull();
		assertThat(GoogleScriptEmailSender.secretProblem("x".repeat(32))).isNull();
		assertThat(GoogleScriptEmailSender.secretProblem(null)).isEqualTo("is not set");
		assertThat(GoogleScriptEmailSender.secretProblem("  ")).isEqualTo("is not set");
		assertThat(GoogleScriptEmailSender.secretProblem("x".repeat(31))).isEqualTo("must be at least 32 characters long");
		// spaces around it do not count
		assertThat(GoogleScriptEmailSender.secretProblem(" " + "x".repeat(31) + " ")).isNotNull();

		assertThatThrownBy(() -> new GoogleScriptEmailSender(URL, " ", "A")).isInstanceOf(IllegalStateException.class)
			.hasMessage("app.mail.provider=google: app.mail.google.secret (GOOGLE_SCRIPT_SECRET) is not set");
		assertThatThrownBy(() -> new GoogleScriptEmailSender(URL, "short-secret-0123456789", "A"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("GOOGLE_SCRIPT_SECRET")
			.hasMessageContaining("at least 32 characters")
			.hasMessageNotContaining("short-secret");
	}

	// ------------------------------------------------------------------ helpers

	/** A sender without a network: every round trip gives the next scripted outcome. */
	private static final class ScriptedSender extends GoogleScriptEmailSender {

		private final Deque<Object> outcomes;

		final List<HttpRequest> requests = new ArrayList<>();

		ScriptedSender(Object... outcomes) {
			super(URL, SECRET, "Spend Analyzer");
			this.outcomes = new ArrayDeque<>(List.of(outcomes));
		}

		@Override
		@SuppressWarnings("unchecked")
		HttpResponse<String> exchange(HttpRequest request) throws IOException {
			requests.add(request);
			Object outcome = outcomes.remove();
			if (outcome instanceof IOException failure) {
				throw failure;
			}
			return (HttpResponse<String>) outcome;
		}

	}

	private static String refused(String word) {
		return "{\"ok\":false,\"error\":\"" + word + "\"}";
	}

	private static HttpResponse<String> answer(int status, String body) {
		return response(status, body, Map.of("content-type", List.of("application/json; charset=utf-8")));
	}

	/** Google's "the answer is over there". A null location means: no Location header at all. */
	private static HttpResponse<String> redirect(int status, String location) {
		return response(status, "<HTML><BODY>Moved Temporarily</BODY></HTML>",
				location == null ? Map.of() : Map.of("Location", List.of(location)));
	}

	private static HttpResponse<String> response(int status, String body, Map<String, List<String>> headers) {
		return new HttpResponse<>() {
			@Override
			public int statusCode() {
				return status;
			}

			@Override
			public String body() {
				return body;
			}

			@Override
			public HttpRequest request() {
				return null;
			}

			@Override
			public Optional<HttpResponse<String>> previousResponse() {
				return Optional.empty();
			}

			@Override
			public HttpHeaders headers() {
				return HttpHeaders.of(headers, (name, value) -> true);
			}

			@Override
			public Optional<SSLSession> sslSession() {
				return Optional.empty();
			}

			@Override
			public URI uri() {
				return URI.create(URL);
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_1_1;
			}
		};
	}

}
