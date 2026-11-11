package com.spendanalyzer.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

	/** The only scripts that may touch localStorage: two harmless choices, and "light" or "dark". */
	private static final Set<String> MAY_USE_LOCAL_STORAGE = Set.of("js/store.js", "js/theme-init.js");

	/** The script that sets the theme before the first paint. */
	private static final String THEME_SCRIPT = "/js/theme-init.js";

	/** The script that logs the user out after a time without activity. */
	private static final String IDLE_SCRIPT = "js/idle.js";

	/** A page's script tag, giving the path without the leading slash. */
	private static final Pattern SCRIPT_SRC = Pattern.compile("<script\\b[^>]*\\bsrc\\s*=\\s*\"/([^\"]+)\"",
			Pattern.CASE_INSENSITIVE);

	/** A static ES module import of one of the site's own scripts. */
	private static final Pattern IMPORT_FROM = Pattern.compile("\\bimport\\b[^;]*?[\"']/(js/[^\"']+)[\"']");

	private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

	/** The two blocks in base.css that give every colour its name: light, then dark. */
	private static final Pattern LIGHT_TOKENS = Pattern.compile("(?m)^:root\\s*\\{([^}]*)\\}");

	private static final Pattern DARK_TOKENS = Pattern.compile("(?m)^:root\\[data-theme=\"dark\"\\]\\s*\\{([^}]*)\\}");

	private static final Pattern TOKEN = Pattern.compile("(--[a-z0-9-]+)\\s*:\\s*([^;]+);");

	private static final Pattern TOKEN_USE = Pattern.compile("var\\(\\s*(--[a-z0-9-]+)");

	/** Every CSS colour word (the 148 named colours, both spellings of grey). */
	private static final String NAMED_COLOURS = "aliceblue|antiquewhite|aqua|aquamarine|azure|beige|bisque|black|blanchedalmond|blue|blueviolet|brown"
			+ "|burlywood|cadetblue|chartreuse|chocolate|coral|cornflowerblue|cornsilk|crimson|cyan|darkblue"
			+ "|darkcyan|darkgoldenrod|darkgray|darkgreen|darkgrey|darkkhaki|darkmagenta|darkolivegreen|darkorange"
			+ "|darkorchid|darkred|darksalmon|darkseagreen|darkslateblue|darkslategray|darkslategrey|darkturquoise"
			+ "|darkviolet|deeppink|deepskyblue|dimgray|dimgrey|dodgerblue|firebrick|floralwhite|forestgreen"
			+ "|fuchsia|gainsboro|ghostwhite|gold|goldenrod|gray|green|greenyellow|grey|honeydew|hotpink|indianred"
			+ "|indigo|ivory|khaki|lavender|lavenderblush|lawngreen|lemonchiffon|lightblue|lightcoral|lightcyan"
			+ "|lightgoldenrodyellow|lightgray|lightgreen|lightgrey|lightpink|lightsalmon|lightseagreen"
			+ "|lightskyblue|lightslategray|lightslategrey|lightsteelblue|lightyellow|lime|limegreen|linen|magenta"
			+ "|maroon|mediumaquamarine|mediumblue|mediumorchid|mediumpurple|mediumseagreen|mediumslateblue"
			+ "|mediumspringgreen|mediumturquoise|mediumvioletred|midnightblue|mintcream|mistyrose|moccasin"
			+ "|navajowhite|navy|oldlace|olive|olivedrab|orange|orangered|orchid|palegoldenrod|palegreen"
			+ "|paleturquoise|palevioletred|papayawhip|peachpuff|peru|pink|plum|powderblue|purple|rebeccapurple|red"
			+ "|rosybrown|royalblue|saddlebrown|salmon|sandybrown|seagreen|seashell|sienna|silver|skyblue|slateblue"
			+ "|slategray|slategrey|snow|springgreen|steelblue|tan|teal|thistle|tomato|turquoise|violet|wheat|white"
			+ "|whitesmoke|yellow|yellowgreen";

	/** A colour written out: #hex, rgb(), hsl() and friends, or a colour word. */
	private static final Pattern RAW_COLOUR = Pattern.compile(
			"#[0-9a-f]{3,8}\\b|\\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color|color-mix)\\s*\\("
					+ "|(?<![\\w-])(?:" + NAMED_COLOURS + ")(?![\\w-])",
			Pattern.CASE_INSENSITIVE);

	/**
	 * A colour the device picks (Canvas, ButtonText ...) used as a value. Looked for only after the
	 * colon of a declaration, never in a selector: "Field" is also the class name ".field".
	 */
	private static final Pattern SYSTEM_COLOUR = Pattern.compile(
			":[^;{}]*?(?<![\\w.#-])(?:accentcolor|accentcolortext|activetext|buttonborder|buttonface|buttontext"
					+ "|canvas|canvastext|field|fieldtext|graytext|highlight|highlighttext|linktext|mark|marktext"
					+ "|selecteditem|selecteditemtext|visitedtext)(?![\\w-])(?![^{]*\\{)",
			Pattern.CASE_INSENSITIVE);

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
		// Only harmless choices may be stored: two through store.js, and "light" or "dark" by theme-init.js
		// (it must run before any module loads, so it cannot go through store.js).
		List<String> problems = new ArrayList<>();
		for (Path script : files(".js")) {
			String js = read(script);
			if (js.contains("sessionStorage") || js.contains("indexedDB") || js.contains("document.cookie")) {
				problems.add(name(script) + " uses sessionStorage, indexedDB or document.cookie");
			}
			if (js.contains("localStorage") && !MAY_USE_LOCAL_STORAGE.contains(name(script))) {
				problems.add(name(script) + " uses localStorage directly; go through store.js");
			}
		}
		failIfAny("No tokens or balances in browser storage", problems);
	}

	@Test
	void bothPagesSetTheThemeBeforeTheFirstPaint() throws IOException {
		// A plain (render-blocking) script in <head>, before any style sheet: no flash of the wrong theme.
		List<String> problems = new ArrayList<>();
		assertTrue(Files.isRegularFile(STATIC.resolve("js/theme-init.js")), "js/theme-init.js is missing");
		for (String page : List.of("index.html", "login.html")) {
			String html = read(STATIC.resolve(page));
			int headEnd = html.indexOf("</head>");
			String head = headEnd < 0 ? "" : html.substring(0, headEnd);
			Matcher tag = Pattern.compile("<script\\b([^>]*)\\bsrc\\s*=\\s*\"" + Pattern.quote(THEME_SCRIPT) + "\"([^>]*)>").matcher(head);
			if (!tag.find()) {
				problems.add(page + " does not load " + THEME_SCRIPT + " in <head>");
				continue;
			}
			String attributes = (tag.group(1) + " " + tag.group(2)).toLowerCase(Locale.ROOT);
			if (attributes.contains("module") || attributes.contains("defer") || attributes.contains("async")) {
				problems.add(page + " loads " + THEME_SCRIPT + " as a module / defer / async script; it must block rendering");
			}
			int sheet = head.indexOf("rel=\"stylesheet\"");
			if (sheet < 0) {
				problems.add(page + " has no style sheet in <head>");
			}
			else if (tag.start() > sheet) {
				problems.add(page + " loads " + THEME_SCRIPT + " after a style sheet");
			}
			if (!head.contains("<meta name=\"theme-color\"") || !head.contains("<meta name=\"color-scheme\" content=\"light dark\">")) {
				problems.add(page + " needs the theme-color meta and <meta name=\"color-scheme\" content=\"light dark\">");
			}
		}
		failIfAny("The theme must be on <html> before the page is painted", problems);
	}

	@Test
	void bothPagesHaveTheLightDarkButton() throws IOException {
		List<String> problems = new ArrayList<>();
		Pattern button = Pattern.compile("<button\\b[^>]*\\bid=\"theme-toggle\"[^>]*>");
		for (String page : List.of("index.html", "login.html")) {
			String html = read(STATIC.resolve(page));
			Matcher found = button.matcher(html);
			if (!found.find()) {
				problems.add(page + " has no <button id=\"theme-toggle\">");
				continue;
			}
			if (!found.group().contains("type=\"button\"") || !found.group().contains("aria-label=\"Switch to ")) {
				problems.add(page + ": the theme button needs type=\"button\" and an aria-label that says what it does");
			}
			int header = html.indexOf("<header class=\"topbar\">");
			int headerEnd = html.indexOf("</header>");
			if (header < 0 || found.start() < header || found.start() > headerEnd) {
				problems.add(page + ": the theme button must be in the top bar");
			}
			if (!html.contains("<script type=\"module\" src=\"/js/theme.js\"></script>")) {
				problems.add(page + " does not load /js/theme.js, which makes the button work");
			}
		}
		failIfAny("Both pages need the light / dark button", problems);
	}

	@Test
	void styleSheetsTakeEveryColourFromTheTokens() throws IOException {
		// Colours are written once, in the two token blocks at the top of base.css (light, then dark).
		// Everywhere else a rule must say var(--name), or a new rule would look wrong in one theme.
		List<String> problems = new ArrayList<>();
		String base = withoutComments(read(STATIC.resolve("css/base.css")));
		Matcher lightBlock = LIGHT_TOKENS.matcher(base);
		Matcher darkBlock = DARK_TOKENS.matcher(base);
		assertTrue(lightBlock.find(), "css/base.css needs a ':root { ... }' block with the light colours");
		assertTrue(darkBlock.find(), "css/base.css needs a ':root[data-theme=\"dark\"] { ... }' block with the dark colours");
		Map<String, String> light = tokens(lightBlock.group(1));
		Map<String, String> dark = tokens(darkBlock.group(1));
		for (String name : dark.keySet()) {
			if (!light.containsKey(name)) {
				problems.add("css/base.css: the dark theme sets " + name + ", which the light theme does not have");
			}
		}
		for (Map.Entry<String, String> token : light.entrySet()) {
			if (RAW_COLOUR.matcher(token.getValue()).find() && !dark.containsKey(token.getKey())) {
				problems.add("css/base.css: " + token.getKey() + " is a colour with no dark value");
			}
		}
		if (!lightBlock.group(1).contains("color-scheme: light") || !darkBlock.group(1).contains("color-scheme: dark")) {
			problems.add("css/base.css: each token block must set color-scheme, so native controls match the theme");
		}
		for (Path sheet : files(".css")) {
			String css = withoutComments(read(sheet));
			if (name(sheet).equals("css/base.css")) {
				css = DARK_TOKENS.matcher(LIGHT_TOKENS.matcher(css).replaceFirst("")).replaceFirst("");
			}
			String[] lines = css.split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				Matcher colour = RAW_COLOUR.matcher(lines[i]);
				if (colour.find()) {
					problems.add(name(sheet) + " writes the colour '" + colour.group() + "' in: " + lines[i].trim());
				}
				if (SYSTEM_COLOUR.matcher(lines[i]).find()) {
					problems.add(name(sheet) + " uses a colour of the device, not a token, in: " + lines[i].trim());
				}
				Matcher use = TOKEN_USE.matcher(lines[i]);
				while (use.find()) {
					if (!light.containsKey(use.group(1))) {
						problems.add(name(sheet) + " uses " + use.group(1) + ", which css/base.css does not define");
					}
				}
			}
		}
		failIfAny("Every colour must be a token from the top of css/base.css", problems);
	}

	@Test
	void theBrowserBarColourFollowsTheBrandToken() throws IOException {
		// theme-init.js cannot read the style sheet before it loads, so it repeats the top bar colour.
		String base = withoutComments(read(STATIC.resolve("css/base.css")));
		Matcher lightBlock = LIGHT_TOKENS.matcher(base);
		Matcher darkBlock = DARK_TOKENS.matcher(base);
		assertTrue(lightBlock.find() && darkBlock.find(), "css/base.css needs both token blocks");
		String lightBrand = tokens(lightBlock.group(1)).get("--brand");
		String darkBrand = tokens(darkBlock.group(1)).get("--brand");
		String script = read(STATIC.resolve("js/theme-init.js"));
		List<String> problems = new ArrayList<>();
		if (lightBrand == null || !script.contains("light: \"" + lightBrand + "\"")) {
			problems.add("js/theme-init.js: the light bar colour must be --brand of the light theme (" + lightBrand + ")");
		}
		if (darkBrand == null || !script.contains("dark: \"" + darkBrand + "\"")) {
			problems.add("js/theme-init.js: the dark bar colour must be --brand of the dark theme (" + darkBrand + ")");
		}
		for (String page : List.of("index.html", "login.html")) {
			if (!read(STATIC.resolve(page)).contains("<meta name=\"theme-color\" content=\"" + lightBrand + "\">")) {
				problems.add(page + ": <meta name=\"theme-color\"> must start as --brand of the light theme (" + lightBrand + ")");
			}
		}
		failIfAny("The browser's own bar must match the top bar", problems);
	}

	@Test
	void theIdleLogoutRunsOnTheDashboardOnly() throws IOException {
		// idle.js logs out after a time without activity: it belongs to the logged-in page, never the login page.
		assertTrue(Files.isRegularFile(STATIC.resolve(IDLE_SCRIPT)), IDLE_SCRIPT + " is missing");
		List<String> problems = new ArrayList<>();
		if (!modulesOf("index.html").contains(IDLE_SCRIPT)) {
			problems.add("index.html does not load " + IDLE_SCRIPT + " (directly or through an import)");
		}
		if (modulesOf("login.html").contains(IDLE_SCRIPT)) {
			problems.add("login.html loads " + IDLE_SCRIPT + "; the login page must not log anyone out");
		}
		failIfAny("The idle logout belongs to the dashboard", problems);
	}

	@Test
	void theIdleScriptKeepsToTheRules() throws IOException {
		String js = read(STATIC.resolve(IDLE_SCRIPT));
		List<String> problems = new ArrayList<>();
		for (Pattern banned : BANNED_IN_SCRIPTS) {
			if (banned.matcher(js).find()) {
				problems.add(IDLE_SCRIPT + " matches " + banned.pattern());
			}
		}
		// the last-activity time goes through store.js like the other saved choices
		for (String api : List.of("localStorage", "sessionStorage", "indexedDB", "document.cookie", "innerHTML")) {
			if (js.contains(api)) {
				problems.add(IDLE_SCRIPT + " uses " + api);
			}
		}
		// wall-clock time, so a phone that slept still logs out on time
		if (!js.contains("Date.now()")) {
			problems.add(IDLE_SCRIPT + " must measure time with Date.now()");
		}
		// pressing "Log out" in the warning is not "no activity"
		if (!js.contains("logOut(\"out\")")) {
			problems.add(IDLE_SCRIPT + ": the warning's Log out button must call logOut(\"out\")");
		}
		failIfAny("idle.js keeps to the page rules", problems);
	}

	@Test
	void theLoginPageEndsTheSessionAfterALogoutBeforeAnythingElse() throws IOException {
		// If the dashboard's logout request failed, the login page must not find the session alive and
		// open the dashboard again: it logs out first, because every other call keeps the session alive.
		String js = read(STATIC.resolve("js/login.js"));
		int start = js.indexOf("async function start()");
		assertTrue(start >= 0, "login.js has no start()");
		String body = js.substring(start);
		int logout = body.indexOf("\"/api/auth/logout\"");
		int config = body.indexOf("\"/api/auth/config\"");
		int me = body.indexOf("\"/api/auth/me\"");
		List<String> problems = new ArrayList<>();
		if (logout < 0) {
			problems.add("start() does not send /api/auth/logout after an idle logout or #out");
		} else {
			if (config >= 0 && config < logout) {
				problems.add("start() asks /api/auth/config before it logs out");
			}
			if (me >= 0 && me < logout) {
				problems.add("start() asks /api/auth/me before it logs out");
			}
		}
		if (!body.contains("\"idle\"") || !body.contains("\"#out\"")) {
			problems.add("start() must log out for both ?reason=idle and #out");
		}
		failIfAny("The login page ends the session after a logout", problems);
	}

	/** Every script a page runs: its script tags and, through them, every module they import. */
	private static Set<String> modulesOf(String page) throws IOException {
		Set<String> found = new LinkedHashSet<>();
		ArrayDeque<String> next = new ArrayDeque<>();
		Matcher tag = SCRIPT_SRC.matcher(read(STATIC.resolve(page)));
		while (tag.find()) {
			next.add(tag.group(1));
		}
		while (!next.isEmpty()) {
			String script = next.poll();
			if (!found.add(script)) {
				continue;
			}
			Path file = STATIC.resolve(script);
			if (!Files.isRegularFile(file)) {
				continue;
			}
			Matcher imported = IMPORT_FROM.matcher(read(file));
			while (imported.find()) {
				next.add(imported.group(1));
			}
		}
		return found;
	}

	private static String withoutComments(String css) {
		return CSS_COMMENT.matcher(css.replace("\r\n", "\n")).replaceAll("");
	}

	private static Map<String, String> tokens(String block) {
		Map<String, String> found = new LinkedHashMap<>();
		Matcher token = TOKEN.matcher(block);
		while (token.find()) {
			found.put(token.group(1), token.group(2).trim());
		}
		return found;
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
