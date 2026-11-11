package com.spendanalyzer.auth.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production mail through the owner's own Gmail: a small Google Apps Script web app
 * (deploy/google-apps-script/Code.gs in this repository) takes one HTTPS POST and sends the mail
 * with the owner's Gmail account. No mail account besides Gmail and no domain name are needed, and
 * everything goes over port 443 (the free host blocks the SMTP ports).
 * <p>
 * How Google answers: the script runs during the POST and Google replies "302, the answer is at
 * https://script.googleusercontent.com/...". That second address is then read with a plain GET.
 * The two steps are made one by one here instead of by the HTTP client's automatic redirects, for
 * two reasons: the secret is in the POST body and must never be posted to a second address, and
 * only a failure of the first step may be tried again (after it the mail may already be sent).
 * <p>
 * The mail counts as sent only when the last answer is HTTP 200 with the JSON {@code {"ok":true}}.
 * Google also answers 200 with an HTML page (a sign-in page, "Authorization needed"), so the status
 * alone proves nothing. A failure is reported with the HTTP status and the script's short error
 * word only: never the secret, the web app address, the request or anything of the answer's text.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "google")
public class GoogleScriptEmailSender implements EmailSender {

	/** The script (Code.gs) refuses a shorter SECRET as well. */
	public static final int MIN_SECRET_LENGTH = 32;

	/** The limits of Code.gs for one mail. The fixed texts of {@link MailTemplates} are far below them. */
	static final int MAX_SUBJECT_LENGTH = 200;

	static final int MAX_TEXT_LENGTH = 10_000;

	static final int MAX_HTML_LENGTH = 50_000;

	/** Covers the TLS handshake as well: slow on a cold JVM with a tenth of a CPU. */
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

	/** The script starts cold too and sends the mail before it answers. */
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(40);

	/** Google redirects once. A few more are followed, a loop is not. */
	static final int MAX_REDIRECTS = 3;

	/** The real answer has about 40 characters. Anything long is an HTML page and is not parsed. */
	private static final int MAX_ANSWER_LENGTH = 2000;

	/** Only a web app of script.google.com: a typing mistake can never point the app at another host. */
	private static final Pattern WEB_APP_URL = Pattern
		.compile("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec");

	/** How the web app URL of a school or company (Google Workspace) account starts. */
	private static final String WORKSPACE_URL_START = "https://script.google.com/a/macros/";

	/** Where Google keeps the answers of scripts (script.googleusercontent.com). */
	private static final String ANSWER_HOST_SUFFIX = ".googleusercontent.com";

	/** What an error word of the script looks like; anything else in its place is not repeated. */
	private static final Pattern ERROR_WORD = Pattern.compile("[a-z_]{1,30}");

	private static final Pattern HOST_NAME = Pattern.compile("[a-z0-9.-]{1,100}");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final URI url;

	private final String secret;

	private final String fromName;

	/** Created with the first mail: an idle instance then has no HTTP client threads at all. */
	private HttpClient client;

	@Autowired
	public GoogleScriptEmailSender(@Value("${app.mail.google.script-url:}") String scriptUrl,
			@Value("${app.mail.google.secret:}") String secret,
			@Value("${app.mail.from-name:Spend Analyzer}") String fromName) {
		this(webAppUrl(scriptUrl), secret, fromName);
	}

	/** For tests: any address, so that a local server can stand in for Google. */
	GoogleScriptEmailSender(URI url, String secret, String fromName) {
		String problem = secretProblem(secret);
		if (problem != null) {
			throw new IllegalStateException(
					"app.mail.provider=google: app.mail.google.secret (GOOGLE_SCRIPT_SECRET) " + problem);
		}
		this.url = url;
		this.secret = secret.strip();
		this.fromName = fromName == null || fromName.isBlank() ? "Spend Analyzer" : fromName.strip();
	}

	private static URI webAppUrl(String scriptUrl) {
		String problem = urlProblem(scriptUrl);
		if (problem != null) {
			throw new IllegalStateException(
					"app.mail.provider=google: app.mail.google.script-url (GOOGLE_SCRIPT_URL) " + problem);
		}
		return URI.create(scriptUrl.strip());
	}

	/**
	 * What is wrong with this web app address, or null when it is fine. The text never repeats the
	 * address: whoever knows it can call the script.
	 */
	public static String urlProblem(String scriptUrl) {
		if (scriptUrl == null || scriptUrl.isBlank()) {
			return "is not set";
		}
		String value = scriptUrl.strip();
		if (WEB_APP_URL.matcher(value).matches()) {
			return null;
		}
		if (value.startsWith(WORKSPACE_URL_START)) {
			// not accepted: such a web app may ask for a sign-in of that organisation
			return "is the URL of a school or company Google account; "
					+ "make the script with a personal @gmail.com account";
		}
		if (value.endsWith("/dev")) {
			return "must be the web app URL that ends with /exec, not the test URL that ends with /dev";
		}
		return "must look like https://script.google.com/macros/s/.../exec "
				+ "(in the script editor: Deploy > Manage deployments > Web app URL)";
	}

	/** What is wrong with this shared secret, or null when it is fine. Never repeats the secret. */
	public static String secretProblem(String secret) {
		if (secret == null || secret.isBlank()) {
			return "is not set";
		}
		if (secret.strip().length() < MIN_SECRET_LENGTH) {
			return "must be at least " + MIN_SECRET_LENGTH + " characters long";
		}
		return null;
	}

	@Override
	public void send(EmailMessage message) {
		HttpResponse<String> response = post(message);
		URI at = url;
		int redirects = 0;
		while (pointsToTheAnswer(response.statusCode())) {
			if (++redirects > MAX_REDIRECTS) {
				throw new EmailException("Google Apps Script redirected more than " + MAX_REDIRECTS + " times");
			}
			at = answerAddress(at, response);
			response = fetch(at);
		}
		requireSent(response.statusCode(), response.body());
	}

	/** One HTTP round trip. On its own so that a test can stand in for the network. */
	HttpResponse<String> exchange(HttpRequest request) throws IOException, InterruptedException {
		return client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/** The call that makes the script send this mail. The secret is in the body only. */
	HttpRequest request(EmailMessage message) {
		return HttpRequest.newBuilder(url)
			.timeout(REQUEST_TIMEOUT)
			// without this header the script does not get the body
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body(message), StandardCharsets.UTF_8))
			.build();
	}

	/** The JSON body Code.gs expects. Built by Jackson, so every text is escaped properly. */
	String body(EmailMessage message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("secret", secret);
		body.put("to", message.to());
		body.put("subject", message.subject());
		body.put("text", message.text());
		body.put("html", message.html());
		body.put("fromName", fromName);
		return JSON.writeValueAsString(body);
	}

	/** Step one: the POST. The script runs, and may send the mail, before this returns. */
	private HttpResponse<String> post(EmailMessage message) {
		try {
			try {
				return exchange(request(message));
			}
			catch (HttpConnectTimeoutException | ConnectException ex) {
				// No connection was made, so nothing was transmitted: one more try cannot send the
				// mail twice. Any other failure may have delivered it and is not retried.
				return exchange(request(message));
			}
		}
		catch (HttpConnectTimeoutException | ConnectException ex) {
			throw new EmailException("Google Apps Script could not be reached: " + ex.getClass().getSimpleName());
		}
		catch (HttpTimeoutException ex) {
			throw new EmailException("Google Apps Script did not answer in time (the mail may still have been sent)");
		}
		catch (IOException | UncheckedIOException ex) {
			// only the exception type: nothing of the request may end up in the log
			throw new EmailException("Google Apps Script could not be reached: " + ex.getClass().getSimpleName()
					+ " (the mail may still have been sent)");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new EmailException("Interrupted while sending through Google Apps Script");
		}
	}

	/** Step two: read the stored answer. A GET without a body: it carries no secret and sends no mail. */
	private HttpResponse<String> fetch(URI answer) {
		HttpRequest request = HttpRequest.newBuilder(answer).timeout(REQUEST_TIMEOUT).GET().build();
		try {
			try {
				return exchange(request);
			}
			catch (HttpConnectTimeoutException | ConnectException ex) {
				// reading the answer again cannot send a second mail
				return exchange(request);
			}
		}
		catch (IOException | UncheckedIOException ex) {
			throw new EmailException("Google Apps Script took the request but its answer could not be read: "
					+ ex.getClass().getSimpleName() + " (the mail may have been sent)");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new EmailException("Interrupted while reading the answer of Google Apps Script");
		}
	}

	/**
	 * 301, 302 and 303 mean "fetch the answer over there with GET". 307 and 308 would mean "POST the
	 * same body there again": that is never done, they are reported as a failure.
	 */
	private static boolean pointsToTheAnswer(int status) {
		return status == 301 || status == 302 || status == 303;
	}

	/** Where the redirect points, if that is a place Google keeps script answers. */
	private URI answerAddress(URI from, HttpResponse<String> redirect) {
		int status = redirect.statusCode();
		String location = redirect.headers().firstValue("location").orElse("").strip();
		URI target;
		try {
			target = location.isEmpty() ? null : from.resolve(location);
		}
		catch (IllegalArgumentException ex) {
			target = null;
		}
		if (target == null || target.getHost() == null || target.getScheme() == null) {
			throw new EmailException("Google Apps Script answered HTTP " + status + " without an address to go to");
		}
		if (!trusted(target)) {
			String host = target.getHost().toLowerCase(Locale.ROOT);
			String shown = HOST_NAME.matcher(host).matches() ? host : "another host";
			String hint = host.equals("accounts.google.com")
					? ": Google asks for a sign-in, so \"Who has access\" of the web app is not \"Anyone\"" : "";
			throw new EmailException("Google Apps Script answered HTTP " + status + " and pointed to " + shown
					+ " instead of the script's answer" + hint);
		}
		return target;
	}

	/** The web app's own server, or Google's host for script answers over HTTPS. */
	private boolean trusted(URI target) {
		String host = target.getHost().toLowerCase(Locale.ROOT);
		boolean sameServer = target.getScheme().equalsIgnoreCase(url.getScheme())
				&& host.equalsIgnoreCase(url.getHost()) && target.getPort() == url.getPort();
		return sameServer || ("https".equalsIgnoreCase(target.getScheme()) && host.endsWith(ANSWER_HOST_SUFFIX));
	}

	/**
	 * Sent means: HTTP 200 and a JSON object with {@code "ok": true}. Everything else is a failure,
	 * reported with the status or the script's error word and a hint what to look at.
	 */
	static void requireSent(int status, String responseBody) {
		if (status != 200) {
			throw new EmailException("Google Apps Script answered HTTP " + status + statusHint(status));
		}
		JsonNode answer = jsonObject(responseBody);
		if (answer == null) {
			throw new EmailException("Google Apps Script answered HTTP 200 with a page instead of JSON (not_json): "
					+ "\"Who has access\" of the web app is not \"Anyone\", or its permission to send mail was removed");
		}
		JsonNode ok = answer.get("ok");
		if (ok != null && ok.isBoolean() && ok.booleanValue()) {
			return;
		}
		String word = errorWord(answer.get("error"));
		throw new EmailException("Google Apps Script did not send the mail: " + word + errorHint(word));
	}

	/** The answer as a JSON object, or null when it is anything else (an HTML page, a list, nothing). */
	private static JsonNode jsonObject(String responseBody) {
		if (responseBody == null || responseBody.length() > MAX_ANSWER_LENGTH) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(responseBody);
			return node != null && node.isObject() ? node : null;
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	private static String errorWord(JsonNode error) {
		if (error == null || !error.isString()) {
			return "unknown_error";
		}
		String word = error.asString();
		return ERROR_WORD.matcher(word).matches() ? word : "unknown_error";
	}

	private static String errorHint(String word) {
		return switch (word) {
			case "not_configured" ->
				" (the script has no script property SECRET of at least " + MIN_SECRET_LENGTH + " characters)";
			case "unauthorized" -> " (the script property SECRET and GOOGLE_SCRIPT_SECRET are not the same)";
			case "quota" -> " (the Gmail account has used up its mails for today; it works again within 24 hours)";
			case "send_failed" -> " (Gmail refused the mail; run checkSetup in the script editor)";
			case "bad_json", "bad_to", "bad_subject", "bad_text", "bad_html" ->
				" (the script did not accept the request; is the deployed version the Code.gs of this repository?)";
			default -> "";
		};
	}

	private static String statusHint(int status) {
		if (status == 404) {
			return " (GOOGLE_SCRIPT_URL is wrong, or the web app deployment was removed)";
		}
		if (status == 401 || status == 403) {
			return " (\"Who has access\" of the web app must be \"Anyone\")";
		}
		if (status == 429 || status / 100 == 5) {
			return " (a problem at Google, usually temporary)";
		}
		return "";
	}

	private synchronized HttpClient client() {
		if (client == null) {
			// Redirect.NEVER on purpose: send() follows the redirect itself, with GET and without the secret
			client = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		}
		return client;
	}

	@PreDestroy
	synchronized void close() {
		if (client != null) {
			client.shutdownNow();
			client = null;
		}
	}

}
