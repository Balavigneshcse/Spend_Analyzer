package com.spendanalyzer.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the browser files under {@code src/main/resources/static} against the two things the
 * Content-Security-Policy and the "API returns raw user text" rule forbid: inline code or styles
 * in the pages, and any way of turning text into HTML or code in the scripts.
 * <p>
 * Plain JUnit: reads the files, starts nothing.
 */
class StaticAssetsTest {

	private static final Path STATIC = Path.of("src", "main", "resources", "static");

	/** Ways to turn a string into markup or code. User text must only ever be set as text. */
	private static final List<Pattern> BANNED_IN_SCRIPTS = List.of(Pattern.compile("\\binnerHTML\\b"),
			Pattern.compile("\\bouterHTML\\b"), Pattern.compile("\\binsertAdjacentHTML\\b"),
			Pattern.compile("\\bdocument\\s*\\.\\s*write(ln)?\\b"), Pattern.compile("\\beval\\s*\\("),
			Pattern.compile("\\bnew\\s+Function\\b"), Pattern.compile("\\bcreateContextualFragment\\b"),
			Pattern.compile("\\bDOMParser\\b"), Pattern.compile("\\bsrcdoc\\b"),
			Pattern.compile("setAttribute(NS)?\\s*\\(\\s*[^,)]*[\"'`]\\s*on[a-z]", Pattern.CASE_INSENSITIVE),
			Pattern.compile("setTimeout\\s*\\(\\s*[\"'`]"), Pattern.compile("setInterval\\s*\\(\\s*[\"'`]"),
			Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE));

	private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b([^>]*)>(.*?)</script\\s*>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern SRC_ATTRIBUTE = Pattern.compile("\\bsrc\\s*=", Pattern.CASE_INSENSITIVE);

	private static final Pattern STYLE_TAG = Pattern.compile("<style\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern STYLE_ATTRIBUTE = Pattern.compile("\\sstyle\\s*=", Pattern.CASE_INSENSITIVE);

	/** An event attribute such as onclick= inside a tag. */
	private static final Pattern EVENT_ATTRIBUTE = Pattern.compile("<[^>]*\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);

	private static final Pattern ABSOLUTE_URL = Pattern.compile("(?:https?|wss?|ftp):/+[a-z0-9.-]+",
			Pattern.CASE_INSENSITIVE);

	/** Any tag attribute that loads or links something. */
	private static final Pattern URL_ATTRIBUTE = Pattern.compile(
			"\\b(?:src|href|action|srcset|poster|data|formaction|content)\\s*=\\s*[\"']?\\s*((?:https?:)?//[^\"'\\s>]+)",
			Pattern.CASE_INSENSITIVE);

	/** url(...) or @import with another host in a style sheet. */
	private static final Pattern CSS_REMOTE = Pattern.compile(
			"(?:url\\(\\s*[\"']?\\s*(?:https?:)?//|@import\\s+[\"']?\\s*(?:https?:)?//)", Pattern.CASE_INSENSITIVE);

	@Test
	void theTwoPagesExist() {
		assertTrue(Files.isRegularFile(STATIC.resolve("index.html")), "index.html is missing");
		assertTrue(Files.isRegularFile(STATIC.resolve("login.html")), "login.html is missing");
		assertTrue(Files.isRegularFile(STATIC.resolve("favicon.svg")), "favicon.svg is missing");
	}

	@Test
	void scriptsNeverTurnTextIntoMarkupOrCode() throws IOException {
		List<Path> scripts = files(".js");
		assertFalse(scripts.isEmpty(), "no .js files found under " + STATIC);
		List<String> problems = new ArrayList<>();
		for (Path script : scripts) {
			String[] lines = read(script).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				for (Pattern banned : BANNED_IN_SCRIPTS) {
					if (banned.matcher(lines[i]).find()) {
						problems.add(name(script) + ":" + (i + 1) + " matches " + banned.pattern());
					}
				}
			}
		}
		failIfAny("Scripts must put text in the page with textContent / createElement only", problems);
	}

	@Test
	void pagesHaveNoInlineCodeOrStyles() throws IOException {
		List<Path> pages = files(".html");
		assertFalse(pages.isEmpty(), "no .html files found under " + STATIC);
		List<String> problems = new ArrayList<>();
		for (Path page : pages) {
			String html = read(page);
			String where = name(page);
			if (STYLE_TAG.matcher(html).find()) {
				problems.add(where + " has a <style> block");
			}
			if (STYLE_ATTRIBUTE.matcher(html).find()) {
				problems.add(where + " has a style= attribute");
			}
			if (EVENT_ATTRIBUTE.matcher(html).find()) {
				problems.add(where + " has an on...= event attribute");
			}
			Matcher script = SCRIPT_TAG.matcher(html);
			while (script.find()) {
				if (!SRC_ATTRIBUTE.matcher(script.group(1)).find()) {
					problems.add(where + " has an inline <script> without src");
				}
				else if (!script.group(2).isBlank()) {
					problems.add(where + " has code inside a <script src>");
				}
			}
			if (html.toLowerCase(Locale.ROOT).contains("javascript:")) {
				problems.add(where + " has a javascript: URL");
			}
		}
		failIfAny("The Content-Security-Policy forbids inline scripts and styles", problems);
	}

	@Test
	void pagesLoadNothingFromOtherHosts() throws IOException {
		List<String> problems = new ArrayList<>();
		for (Path page : files(".html")) {
			String html = read(page);
			Matcher attribute = URL_ATTRIBUTE.matcher(html);
			while (attribute.find()) {
				problems.add(name(page) + " points at another host: " + attribute.group(1));
			}
			Matcher url = ABSOLUTE_URL.matcher(html);
			while (url.find()) {
				problems.add(name(page) + " contains an absolute URL: " + url.group());
			}
		}
		for (Path sheet : files(".css")) {
			if (CSS_REMOTE.matcher(read(sheet)).find()) {
				problems.add(name(sheet) + " loads something from another host");
			}
		}
		for (Path script : files(".js")) {
			String js = read(script);
			Matcher url = ABSOLUTE_URL.matcher(js);
			while (url.find()) {
				// the SVG namespace is a name, not a download
				if (!url.group().equalsIgnoreCase("http://www.w3.org")) {
					problems.add(name(script) + " contains an absolute URL: " + url.group());
				}
			}
		}
		failIfAny("Everything must come from this site", problems);
	}

	@Test
	void pagesKeepToThePublicPaths() throws IOException {
		// SecurityConfig serves only these; anything else is refused.
		List<String> problems = new ArrayList<>();
		try (Stream<Path> all = Files.walk(STATIC)) {
			for (Path file : all.filter(Files::isRegularFile).toList()) {
				String path = "/" + STATIC.relativize(file).toString().replace('\\', '/');
				boolean allowed = path.equals("/index.html") || path.equals("/login.html") || path.equals("/favicon.svg")
						|| path.equals("/favicon.ico") || path.equals("/manifest.webmanifest")
						|| path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/img/");
				if (!allowed) {
					problems.add(path + " is not under a publicly served path");
				}
			}
		}
		failIfAny("Static files outside the served paths can never load", problems);
	}

	@Test
	void scriptsKeepNothingSensitiveInBrowserStorage() throws IOException {
		// Only two harmless choices may be stored, and only through store.js.
		List<String> problems = new ArrayList<>();
		for (Path script : files(".js")) {
			String js = read(script);
			if (js.contains("sessionStorage") || js.contains("indexedDB") || js.contains("document.cookie")) {
				problems.add(name(script) + " uses sessionStorage, indexedDB or document.cookie");
			}
			if (js.contains("localStorage") && !name(script).equals("js/store.js")) {
				problems.add(name(script) + " uses localStorage directly; go through store.js");
			}
		}
		failIfAny("No tokens or balances in browser storage", problems);
	}

	private static List<Path> files(String extension) throws IOException {
		try (Stream<Path> all = Files.walk(STATIC)) {
			return all.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
				.sorted()
				.toList();
		}
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	private static String name(Path file) {
		return STATIC.relativize(file).toString().replace('\\', '/');
	}

	private static void failIfAny(String rule, List<String> problems) {
		if (!problems.isEmpty()) {
			fail(rule + ":\n  " + String.join("\n  ", problems));
		}
	}

}
